package com.boke.qcmeta.store;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.message.MessageStates;
import com.boke.qcmeta.model.StoreModels.AttemptRecord;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.boke.qcmeta.model.StoreModels.NewMessage;
import com.boke.qcmeta.model.StoreModels.OutboxRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.boke.qcmeta.mapper.MessageMapper;
import com.boke.qcmeta.model.db.MessageCommands.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
/**
 * 负责消息、活动和 MQ outbox 的 MySQL 操作。
 *
 * <p>最关键的规则是：业务消息和对应的 outbox 必须在同一个事务里保存。这样即使服务刚写完消息就断电，
 * 后台仍能从 outbox 找到它；事务失败时两条记录也会一起回滚。</p>
 */
@Repository
public class MessageRepository {

    private final MessageMapper database;
    private final TransactionTemplate transactions;
    private final MessageEventWriter events;

    public MessageRepository(MessageMapper database, TransactionTemplate transactions,
                             MessageEventWriter events) {
        this.database = database;
        this.transactions = transactions;
        this.events = events;
    }

    public InsertResult insertMessage(NewMessage message) {
        // 事务的边界放在这里，调用方不需要记住还要额外写一条 MQ 任务。
        InsertResult result = transactions.execute(status -> {
            int inserted = insertMessageRow(message);
            if (inserted == 0) {
                return new InsertResult(getByRequest(message.businessId(), message.sendRequestId()), true);
            }
            database.insertOutbox(message);
            events.write(message.messageId());
            return new InsertResult(get(message.messageId()), false);
        });
        if (result == null) {
            throw new IllegalStateException("保存消息失败");
        }
        return result;
    }

    private int insertMessageRow(NewMessage message) {
        try {
            return database.insertMessage(message);
        } catch(org.springframework.dao.DuplicateKeyException duplicate) {
            return 0;
        }
    }

    public void insertCampaignMessages(List<NewMessage> rows) {
        transactions.executeWithoutResult(tx -> {
            try {
                // 分块限制单条 SQL 大小，所有块仍随活动一起提交或回滚。
                for (int start = 0; start < rows.size();) {
                    int end = start;
                    long estimatedBytes = 0;
                    while (end < rows.size() && end - start < 100) {
                        NewMessage row = rows.get(end);
                        long bytes = 4096L + 3L * row.content().toString().length();
                        if (end > start && estimatedBytes + bytes > 1_048_576) break;
                        estimatedBytes += bytes;
                        end++;
                    }
                    List<NewMessage> batch = rows.subList(start, end);
                    database.insertMessages(batch);
                    database.insertOutboxes(batch);
                    events.write(batch.stream().map(NewMessage::messageId).toList());
                    start = end;
                }
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                throw ApiException.conflict("活动收件人的发送请求编号已存在");
            }
        });
    }

    public MessageRecord findByRequest(String businessId, String requestId) {
        List<MessageRecord> rows = database.findByRequest(businessId, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public String fingerprint(String messageId) {
        return database.fingerprint(messageId);
    }
    public void applyProviderResult(String messageId, String providerId, String wamid, String observed,
                                    Instant at, String errorCode) {
        transactions.executeWithoutResult(tx -> {
            MessageRecord current = database.lockMessage(messageId);
            if (current == null) throw ApiException.notFound("消息不存在: " + messageId);
            String incoming = observed == null ? "" : observed.toUpperCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("ACCEPTED","SENT","DELIVERED","READ","FAILED").contains(incoming)) return;
            String next = MessageStates.merge(current.state(), incoming);
            ProviderResult change = new ProviderResult(messageId, next, blank(providerId), blank(wamid), incoming, at, nullable(errorCode));
            if (!changesMessage(current, change)) return;
            database.applyProviderResult(change);
            events.write(messageId);
        });
    }

    private static boolean changesMessage(MessageRecord current, ProviderResult change) {
        if (!current.state().equals(change.state()) || current.submittedAt() == null
                || (!change.providerId().isEmpty() && !change.providerId().equals(current.providerMessageId()))
                || (!change.wamid().isEmpty() && !change.wamid().equals(current.whatsappMessageId()))) return true;
        // 状态没变也可能补齐晚到的时间；平台编号按原值比较，不受数据库排序规则影响。
        Instant previous = switch (change.incoming()) {
            case "SENT" -> current.sentAt();
            case "DELIVERED" -> current.deliveredAt();
            case "READ" -> current.readAt();
            case "FAILED" -> current.failedAt();
            default -> change.at();
        };
        if (previous == null && change.at() != null) return true;
        return change.incoming().equals("FAILED")
                && (!java.util.Objects.equals(current.lastErrorCode(), change.errorCode())
                    || !"YCloud 明确返回失败，请按错误码核对".equals(current.lastErrorMessage()));
    }

    public String resolveReply(String businessId,String senderId,String recipientType,String recipient,String localId,String senderPhone) {
        List<String> rows = database.resolveReply(new Reply(businessId, senderId, recipientType, recipient, localId, senderPhone));
        if(rows.isEmpty()) throw ApiException.notFound("引用消息不存在或不属于当前会话");
        if(rows.getFirst()==null || rows.getFirst().isBlank()) throw ApiException.conflict("引用消息尚未取得 WhatsApp 编号，请稍后查询再提交");
        return rows.getFirst();
    }

    public MessageRecord get(String messageId) {
        MessageRecord message = database.find(messageId);
        if (message == null) throw ApiException.notFound("消息不存在: " + messageId);
        return message;
    }

    public MessageRecord getByRequest(String businessId, String requestId) {
        MessageRecord message = findByRequest(businessId, requestId);
        if (message == null) throw ApiException.notFound("消息不存在");
        return message;
    }

    public List<OutboxRecord> claimOutbox(String owner, int limit, Instant lockedUntil) {
        // 领取与标记处于同一事务，避免单实例内的后台任务重复领取。
        List<OutboxRecord> result = transactions.execute(status -> {
            List<OutboxRecord> records = database.lockReadyOutbox(Math.max(1, Math.min(limit, 100)));
            if (!records.isEmpty()) database.claimOutbox(records.stream().map(OutboxRecord::outboxId).toList(), owner, lockedUntil);
            return records;
        });
        return result == null ? List.of() : result;
    }

    public void markOutboxPublished(long outboxId, String owner) {
        database.publishOutbox(outboxId, owner);
    }

    public void markOutboxRetry(long outboxId, String owner, String error, Instant nextTime) {
        database.retryOutbox(new OutboxRetry(outboxId, owner, abbreviate(error, 4000), nextTime));
    }

    public int recoverExpiredOutboxClaims() {
        return database.recoverOutbox();
    }

    public ClaimResult claimMessage(String messageId, String owner, Instant lockedUntil) {
        // Kafka 可能重复投递。只有仍处于待发送状态的消息能领取成功，其余任务会被安全跳过。
        int updated = database.claimMessage(messageId, owner, lockedUntil);
        return updated == 0 ? new ClaimResult(null, false) : new ClaimResult(get(messageId), true);
    }

    public int recoverExpiredMessageClaims() {
        Integer count = transactions.execute(tx -> {
            List<String> ids = database.lockExpiredMessages();
            ids.forEach(id -> markUnknown(id,"WORKER_INTERRUPTED","发送进程中断，无法确认 YCloud 是否已经接收"));
            return ids.size();
        });
        return count == null ? 0 : count;
    }

    public void recordAttempt(AttemptRecord attempt) {
        database.insertAttempt(attempt);
    }

    public void markAccepted(String messageId, String providerId, String wamid, Instant at) {
        applyProviderResult(messageId,providerId,wamid,"ACCEPTED",at,null);
    }

    public void markFailed(String messageId, String code, String error, Instant at) {
        transactions.executeWithoutResult(tx -> {
        int changed=database.failMessage(new Failure(messageId, nullable(code), abbreviate(error, 65535), at));
        if(changed>0) events.write(messageId);
        });
    }

    public void markUnknown(String messageId, String code, String error) {
        transactions.executeWithoutResult(tx -> {
        int changed=database.markUnknown(new Failure(messageId, nullable(code), abbreviate(error, 65535), null));
        if(changed>0) events.write(messageId);
        });
    }

    public void markRetry(String messageId, String code, String error, Instant nextTime) {
        transactions.executeWithoutResult(status -> {
            int changed=database.retryMessage(new Failure(messageId, nullable(code), abbreviate(error, 65535), nextTime));
            if(changed==0) return;
            events.write(messageId);
            database.resetOutbox(messageId, nextTime);
        });
    }

    public void cancelCampaign(String campaignId) {
        transactions.executeWithoutResult(status -> {
            int updated = database.cancelCampaign(campaignId);
            if (updated == 0) {
                throw ApiException.notFound("活动不存在: " + campaignId);
            }
            List<String> cancelledIds=database.lockCampaignMessages(campaignId);
            database.cancelCampaignMessages(campaignId);
            events.write(cancelledIds);
            database.cancelCampaignOutbox(campaignId);
        });
    }

    public long countPendingOutbox() {
        Long value = database.countPendingOutbox();
        return value == null ? 0 : value;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public record InsertResult(MessageRecord message, boolean duplicated) {
    }

    public record ClaimResult(MessageRecord message, boolean claimed) {
    }
}
