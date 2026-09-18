package com.boke.qcmeta.message;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.model.ApiModels.MessageView;
import com.boke.qcmeta.model.ApiModels.SubmitMessageRequest;
import com.boke.qcmeta.model.ApiModels.SubmitMessageResponse;
import com.boke.qcmeta.model.MessageContent;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.boke.qcmeta.model.StoreModels.NewMessage;
import com.boke.qcmeta.store.MessageRepository;
import com.boke.qcmeta.store.MessageRepository.InsertResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 接收单条消息，并把可发送的数据保存到 MySQL。
 *
 * <p>这里不直接请求 YCloud。提交成功只表示消息和 MQ 待发送记录已经保存，真正发送由后台任务完成。
 * {@code businessId + sendRequestId} 是防重复键：完全相同的请求会返回原消息，编号相同但内容不同会报冲突。</p>
 */
@Service
public class MessageService {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private final MessageRepository messages;
    private final MessageContentValidator contentValidator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final com.boke.qcmeta.media.AssetService assets;
    private final com.boke.qcmeta.ycloud.YCloudClient ycloud;

    public MessageService(MessageRepository messages,
                          MessageContentValidator contentValidator,
                          ObjectMapper objectMapper, MeterRegistry meters,
                          com.boke.qcmeta.media.AssetService assets,
                          com.boke.qcmeta.ycloud.YCloudClient ycloud) {
        this.messages = messages;
        this.contentValidator = contentValidator;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.assets=assets;this.ycloud=ycloud;
    }

    public SubmitMessageResponse submit(SubmitMessageRequest request) {
        require(request != null, "请求内容不能为空");
        require(!blank(request.businessId()) && request.businessId().length()<=128,"businessId 必须为 1 到 128 个字符");
        require(!blank(request.sendRequestId()) && request.sendRequestId().length()<=255,"sendRequestId 必须为 1 到 255 个字符");
        String fingerprint=CanonicalRequest.message(request,objectMapper);
        MessageRecord previous=messages.findByRequest(trim(request.businessId()),trim(request.sendRequestId()));
        String previousFingerprint = previous == null ? null : messages.fingerprint(previous.messageId());
        if(previousFingerprint!=null) {
            if(!fingerprint.equals(previousFingerprint)) throw ApiException.conflict("sendRequestId 已被不同请求使用");
            return new SubmitMessageResponse(previous.messageId(),previous.state(),previous.acceptedAt(),true);
        }
        validateBase(request);
        require(trim(request.sourceId()).length()<=128 && trim(request.customerId()).length()<=128,"sourceId 或 customerId 超长");
        String recipientType = upper(request.recipientType());
        String deliveryMode = upper(request.deliveryMode());
        validateRecipient(recipientType, request.recipient());
        require(Set.of("ASYNC", "DIRECT").contains(deliveryMode),
                "deliveryMode 只能是 ASYNC 或 DIRECT");
        if (deliveryMode.equals("DIRECT")
                && (Boolean.TRUE.equals(request.filterUnsubscribed())
                || Boolean.TRUE.equals(request.filterBlocked()))) {
            throw ApiException.badRequest("YCloud 直接发送不会执行退订或黑名单过滤，请不要把过滤开关设为 true");
        }
        require(request.content()!=null,"content 不能为空");
        JsonNode resolved=assets.resolveContent(trim(request.businessId()),trim(request.senderId()),trim(request.senderPhone()),objectMapper.valueToTree(request.content()));
        MessageContent prepared=objectMapper.convertValue(resolved,MessageContent.class);
        if(!blank(prepared.replyToMessageId())) {
            require(blank(prepared.replyTo()),"replyToMessageId 和 replyTo 不能同时填写");
            String wamid=messages.resolveReply(trim(request.businessId()),trim(request.senderId()),recipientType,trim(request.recipient()),prepared.replyToMessageId(),trim(request.senderPhone()));
            prepared=new MessageContent(prepared.type(),prepared.text(),prepared.media(),prepared.template(),prepared.location(),prepared.reaction(),prepared.structured(),wamid,null);
        }
        contentValidator.validate(prepared);

        Instant now = Instant.now();
        Instant sendAt = request.sendAt() == null ? now : request.sendAt();
        require(!sendAt.isBefore(now.minus(1, ChronoUnit.MINUTES)),
                "sendAt 不能是已经过去很久的时间");
        // 退订和黑名单由调用方提交前判断；只透传调用方明确指定的 YCloud 过滤参数。
        boolean filterUnsubscribed = Boolean.TRUE.equals(request.filterUnsubscribed());
        boolean filterBlocked = Boolean.TRUE.equals(request.filterBlocked());
        JsonNode content = objectMapper.valueToTree(prepared);
        NewMessage wanted = new NewMessage(
                UUID.randomUUID().toString(), trim(request.sendRequestId()), trim(request.businessId()),
                trim(request.senderId()), trim(request.customerId()), recipientType, trim(request.recipient()),
                upper(request.content().type()), deliveryMode, content, null, null,
                filterUnsubscribed, filterBlocked, sendAt,fingerprint,trim(request.sourceId()),trim(request.senderPhone()),trim(request.wabaId()));
        // Repository 会在同一个 MySQL 事务中写入消息和 MQ outbox，避免只写成功其中一张表。
        InsertResult result = messages.insertMessage(wanted);
        String savedFingerprint = result.duplicated() ? messages.fingerprint(result.message().messageId()) : null;
        if (result.duplicated() && !(savedFingerprint!=null
                ? fingerprint.equals(savedFingerprint)
                : sameRequest(result.message(), wanted, request.sendAt() != null))) {
            meters.counter("qc_meta_messages_submitted_total", "kind", lower(request.content().type()),
                    "result", "conflict").increment();
            throw ApiException.conflict("sendRequestId 已被其他内容使用，请为新消息生成新的编号");
        }
        meters.counter("qc_meta_messages_submitted_total", "kind", lower(request.content().type()),
                "result", result.duplicated() ? "duplicated" : "accepted").increment();
        return new SubmitMessageResponse(result.message().messageId(), result.message().state(),
                result.message().acceptedAt(), result.duplicated());
    }

    public MessageView get(String messageId) {
        try {
            UUID.fromString(messageId);
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("messageId 格式无效");
        }
        return view(messages.get(messageId));
    }

    public MessageView get(String businessId,String messageId) {
        MessageView message=get(messageId);
        if(blank(businessId) || !message.businessId().equals(businessId)) throw ApiException.notFound("消息不存在或不属于当前业务");
        return message;
    }

    public MessageView getByRequest(String businessId,String requestId) {
        require(!blank(businessId) && !blank(requestId),"businessId 和 sendRequestId 不能为空");
        return view(messages.getByRequest(businessId,requestId));
    }

    public MessageView reconcile(String businessId,String messageId) {
        MessageView message=get(businessId,messageId);
        require(!blank(message.providerMessageId()),"尚无 YCloud 消息编号，只能等待关联 Webhook 或人工核对；不能自动重发");
        var result=ycloud.getMessage(message.providerMessageId());
        require(message.providerMessageId().equals(result.id()),"平台查询返回的消息编号不匹配");
        messages.applyProviderResult(messageId,result.id(),result.wamid(),result.status(),result.statusTime(),result.errorCode());
        return get(businessId,messageId);
    }

    public MessageRecord getRecord(String messageId) {
        return messages.get(messageId);
    }

    private void validateBase(SubmitMessageRequest request) {
        require(!blank(request.businessId()) && !blank(request.senderId()) && request.senderId().length()<=128,
                "businessId 和 senderId 必填，senderId 最长 128 字符");
        require(E164.matcher(trim(request.senderPhone())).matches(), "senderPhone 必须为带 + 的 E.164 发送号码");
        require(trim(request.wabaId()).length()<=128, "wabaId 最长 128 字符");
    }

    private void validateRecipient(String recipientType, String recipient) {
        if (recipientType.equals("PHONE")) {
            require(recipient != null && E164.matcher(recipient.trim()).matches(),
                    "手机号码必须使用 E.164 格式，例如 +8613800138000");
        } else if (recipientType.equals("BSUID")) {
            require(recipient != null && !recipient.isBlank() && recipient.length() <= 255,
                    "BSUID 不能为空且不能超过 255 个字符");
        } else {
            throw ApiException.badRequest("recipientType 只能是 PHONE 或 BSUID");
        }
    }

    private boolean sameRequest(MessageRecord existing, NewMessage wanted, boolean checkSendAt) {
        // 发现重复编号时必须逐项比较，不能把修改过收件人或正文的请求误认为安全重试。
        // 无指纹的旧记录也按当前类型规则比较，避免仅因旧类型含大写或空格而拒绝重试。
        JsonNode existingContent = objectMapper.valueToTree(objectMapper.convertValue(existing.content(), MessageContent.class));
        boolean same = existing.businessId().equals(wanted.businessId())
                && existing.senderId().equals(wanted.senderId())
                && existing.customerId().equals(wanted.customerId())
                && existing.recipientType().equals(wanted.recipientType())
                && existing.recipient().equals(wanted.recipient())
                && existing.messageKind().equals(wanted.messageKind())
                && existing.deliveryMode().equals(wanted.deliveryMode())
                && existing.senderPhone().equals(wanted.senderPhone())
                && existing.wabaId().equals(wanted.wabaId())
                && existing.filterUnsubscribed() == wanted.filterUnsubscribed()
                && existing.filterBlocked() == wanted.filterBlocked()
                && existingContent.equals(wanted.content());
        if (!same || !checkSendAt) {
            return same;
        }
        return Duration.between(existing.sendAt(), wanted.sendAt()).abs().toNanos() <= 1_000;
    }

    public static MessageView view(MessageRecord record) {
        return new MessageView(
                record.messageId(), record.sendRequestId(), record.businessId(), record.senderId(),
                record.customerId(), record.recipientType(), record.recipient(), record.messageKind(),
                record.deliveryMode(), record.state(), record.providerMessageId(), record.whatsappMessageId(),
                record.lastErrorCode(), record.lastErrorMessage(), record.acceptedAt(), record.submittedAt(),
                record.sentAt(), record.deliveredAt(), record.readAt(), record.failedAt(),record.senderPhone(),record.wabaId());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw ApiException.badRequest(message);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String upper(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }
}
