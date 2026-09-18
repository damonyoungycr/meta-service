package com.boke.qcmeta.store;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.model.ApiModels.ServiceEvent;
import com.boke.qcmeta.model.StoreModels.WebhookEvent;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.boke.qcmeta.mapper.EventMapper;
import com.boke.qcmeta.model.db.EventRows.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Repository
public class EventRepository {

    private final EventMapper database;
    private final TransactionTemplate transactions;
    private final MessageRepository messages;

    public EventRepository(EventMapper database, TransactionTemplate transactions,
                           MessageRepository messages) {
        this.database = database;
        this.transactions = transactions;
        this.messages=messages;
    }

    public boolean saveWebhookEvent(WebhookEvent event) {
        Boolean duplicated = transactions.execute(status -> {
            int inserted;
            try { inserted = database.insertProviderEvent(event); }
            catch (org.springframework.dao.DuplicateKeyException duplicate) { inserted = 0; }
            if (inserted == 0) {
                return true;
            }

            // 其他回调只需持久化供推送，不做业务查询，也不再补一次处理时间更新。
            return event.eventType().equals("whatsapp.message.updated") && process(event);
        });
        return Boolean.TRUE.equals(duplicated);
    }
    private boolean process(WebhookEvent event) {
        if (event.eventType().equals("whatsapp.message.updated")) {
            MessageRecord owner = findOwner(event);
            if (owner == null) return false;
            messages.applyProviderResult(owner.messageId(),event.providerMessageId(),event.whatsappMessageId(),
                    event.status(),event.occurredAt(),event.errorCode());
        }
        // 入站与资源变化的业务归属由接收方判断，推送不依赖本地状态匹配。
        database.markProcessed(event.eventId());
        return false;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=30000)
    public void recoverUnmatched() {
        List<String> ids=database.pendingProviderEvents();
        for(String id:ids) {
            try { transactions.executeWithoutResult(tx -> {
            Pending raw=database.lockPending(id);
            if(raw==null) return;
            database.deferProviderEvent(id, Instant.now().plusSeconds(Math.min(3600L, 30L * (raw.processAttempts() + 1))));
            process(com.boke.qcmeta.webhook.WebhookController.toEvent(raw.payload(),id));
            }); } catch(RuntimeException failure) {
                // 单条坏事件不能堵住整个队列，错误信息不含客户正文。
                database.deferProviderEvent(id, Instant.now().plusSeconds(300));
            }
        }
    }

    private MessageRecord findOwner(WebhookEvent event) {
        for (Lookup lookup : List.of(
                new Lookup("message_id", event.externalId()),
                new Lookup("provider_message_id", event.providerMessageId()),
                new Lookup("whatsapp_message_id", event.whatsappMessageId()))) {
            if (lookup.value() == null || lookup.value().isBlank()) {
                continue;
            }
            List<MessageRecord> rows = database.findOwner(lookup.column(), lookup.value());
            if (!rows.isEmpty()) {
                var message=rows.getFirst();
                if(event.senderPhone()!=null && !event.senderPhone().isBlank()
                        && !message.senderPhone().equals(event.senderPhone())) continue;
                if(message.providerMessageId()!=null && !message.providerMessageId().isBlank()
                        && event.providerMessageId()!=null && !event.providerMessageId().isBlank()
                        && !message.providerMessageId().equals(event.providerMessageId())) continue;
                return message;
            }
        }
        return null;
    }

    public List<ServiceEvent> list(String businessId,long afterSequence, int limit) {
        if(businessId==null || businessId.isBlank()) throw ApiException.badRequest("businessId 不能为空");
        if(afterSequence!=0) throw ApiException.badRequest("可靠消费请使用 pull/confirm，afterSequence 不能作为消费进度");
        return database.list(businessId, afterSequence, limit);
    }

    public int acknowledge(List<Long> sequences) {
        throw ApiException.badRequest("请使用 pull 返回的 leaseToken 调用 confirm");
    }

    public long countPending() {
        Long value = database.countPending();
        return value == null ? 0 : value;
    }

    public int cleanup(Instant before) {
        Integer count = transactions.execute(status -> {
            int outbox = database.deleteAcknowledged(before);
            // 原始事件ID继续保留，用于平台重试去重和未匹配事件补处理。
            return outbox;
        });
        return count == null ? 0 : count;
    }

    private record Lookup(String column, String value) {
    }

}
