package com.boke.qcmeta.webhook;

import com.boke.qcmeta.config.WebhookProperties;
import com.boke.qcmeta.config.YCloudProperties;
import com.boke.qcmeta.model.StoreModels.WebhookEvent;
import com.boke.qcmeta.store.EventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 接收 YCloud 发来的消息状态和入站消息事件。
 *
 * <p>验签必须使用未经 JSON 解析的原始字节。事件先在一个 MySQL 事务里保存并更新消息状态，成功后
 * 才返回 200；如果落库失败则返回 503，让 YCloud 按它的规则再次通知。</p>
 */
@RestController
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private final WebhookProperties config;
    private final YCloudProperties ycloud;
    private final WebhookVerifier verifier;
    private final EventRepository events;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public WebhookController(WebhookProperties config, WebhookVerifier verifier,
                             EventRepository events, ObjectMapper objectMapper, MeterRegistry meters,
                             YCloudProperties ycloud) {
        this.config = config;
        this.ycloud = ycloud;
        this.verifier = verifier;
        this.events = events;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    @PostMapping(path = "/webhooks/ycloud", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "YCloud-Signature", required = false) String signature) {
        if (rawBody.length > config.maxBodyBytes()) {
            return ResponseEntity.status(413).body(Map.of("error", "请求内容过大"));
        }
        // 先验签再解析 JSON，防止解析过程改变空格或字段顺序后导致签名依据不一致。
        if (!verifier.verify(rawBody, signature, ycloud.webhookSecret(),
                config.signatureTolerance(), Instant.now())) {
            count("unknown", "invalid_signature");
            return ResponseEntity.status(401).body(Map.of("error", "签名校验失败"));
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(rawBody);
        } catch (Exception exception) {
            count("unknown", "invalid_json");
            return ResponseEntity.badRequest().body(Map.of("error", "JSON 格式无效"));
        }
        if (envelope == null || !envelope.isObject()) {
            return ResponseEntity.badRequest().body(Map.of("error", "事件必须是 JSON 对象"));
        }
        String eventId = text(envelope, "id");
        String eventType = text(envelope, "type");
        if (eventId.isBlank() || eventType.isBlank()) {
            count(eventType, "missing_fields");
            return ResponseEntity.badRequest().body(Map.of("error", "事件 id 和 type 不能为空"));
        }
        // VARCHAR 按字符限制长度，超长编号提前拒绝，不能截断后影响事件防重。
        if (eventId.codePointCount(0, eventId.length()) > 150) {
            count(eventType, "invalid_fields");
            return ResponseEntity.badRequest().body(Map.of("error", "事件 id 长度不能超过 150 个字符"));
        }
        if (eventType.length() > 128) {
            count(eventType, "invalid_fields");
            return ResponseEntity.badRequest().body(Map.of("error", "事件 type 长度不能超过 128 个字符"));
        }

        try {
            boolean duplicated = events.saveWebhookEvent(toEvent(envelope, eventId));
            count(eventType, duplicated ? "duplicated" : "accepted");
            return ResponseEntity.ok(Map.of("received", true, "duplicated", duplicated));
        } catch (IllegalArgumentException exception) {
            count(eventType, "invalid_payload");
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (RuntimeException exception) {
            count(eventType, "store_failed");
            log.error("保存 YCloud Webhook 失败，exceptionType={}", exception.getClass().getSimpleName());
            return ResponseEntity.status(503).body(Map.of("error", "事件暂时无法保存"));
        }
    }

    public static WebhookEvent toEvent(JsonNode envelope, String eventId) {
        String eventType=text(envelope,"type");
        Instant occurredAt = parseInstant(text(envelope, "createTime"), Instant.now());
        JsonNode message = null;
        if (eventType.equals("whatsapp.message.updated")) message = envelope.path("whatsappMessage");
        if (message == null || message.isMissingNode() || message.isNull()) {
            return new WebhookEvent(eventId, eventType, envelope, occurredAt,
                    "", "", "", "", "", "", "");
        }
        String id = text(message, "id");
        String wamid = text(message, "wamid");
        String status = text(message, "status").toUpperCase(Locale.ROOT);
        List<String> candidates = switch (status) {
            case "READ" -> List.of("readTime", "deliverTime", "updateTime", "sendTime");
            case "DELIVERED" -> List.of("deliverTime", "updateTime", "sendTime");
            case "SENT" -> List.of("sendTime", "updateTime");
            default -> List.of("updateTime", "sendTime");
        };
        for (String candidate : candidates) {
            Instant parsed = parseInstant(text(message, candidate), null);
            if (parsed != null) {
                occurredAt = parsed;
                break;
            }
        }
        String senderPhone = normalizePhone(text(message, "from"));
        return new WebhookEvent(eventId, eventType, envelope, occurredAt, id, wamid,
                text(message, "externalId"), status, text(message, "errorCode"),
                status.equals("FAILED") ? "YCloud 明确返回失败，请按错误码核对" : "", senderPhone);
    }

    private static Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Instant.parse(value); }
        catch (DateTimeParseException exception) { return fallback; }
    }

    private static String normalizePhone(String value) {
        if (value == null || value.isBlank()) return "";
        return value.startsWith("+") ? value : "+" + value;
    }

    private static String text(JsonNode node, String field) { return node.path(field).asText("").trim(); }

    private void count(String eventType, String result) {
        meters.counter("qc_meta_webhook_events_total", "event_type",
                List.of("whatsapp.message.updated","whatsapp.inbound_message.received").contains(eventType) ? eventType : "other",
                "result", result).increment();
    }
}
