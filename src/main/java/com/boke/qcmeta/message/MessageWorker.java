package com.boke.qcmeta.message;

import com.boke.qcmeta.config.MessageProperties;
import com.boke.qcmeta.config.YCloudProperties;
import com.boke.qcmeta.model.StoreModels.AttemptRecord;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.boke.qcmeta.store.MessageRepository;
import com.boke.qcmeta.store.MessageRepository.ClaimResult;
import com.boke.qcmeta.ycloud.YCloudClient;
import com.boke.qcmeta.ycloud.YCloudClient.YCloudException;
import com.boke.qcmeta.ycloud.YCloudPayloadFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消费 Kafka 任务并调用 YCloud。
 *
 * <p>只有 YCloud 明确返回“没有接收，稍后再试”的 429 才自动重试。网络超时、5xx 或无法解析的成功响应
 * 都可能表示 YCloud 已经收到了消息，所以会标为 UNKNOWN，等待 Webhook 或人工核对，避免重复发给客户。</p>
 */
@Component
public class MessageWorker {

    private static final Logger log = LoggerFactory.getLogger(MessageWorker.class);
    private final MessageRepository messages;
    private final MessageProperties config;
    private final YCloudProperties ycloudSettings;
    private final YCloudPayloadFactory payloadFactory;
    private final YCloudClient ycloud;
    private final MeterRegistry meters;
    private final SenderRateLimiter limiter;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicInteger inFlight = new AtomicInteger();

    public MessageWorker(MessageRepository messages,
                         MessageProperties config, YCloudProperties ycloudSettings, YCloudPayloadFactory payloadFactory,
                         YCloudClient ycloud, MeterRegistry meters,
                         SenderRateLimiter limiter) {
        this.messages = messages;
        this.config = config;
        this.ycloudSettings = ycloudSettings;
        config.validateRequestTimeout(ycloudSettings.requestTimeout());
        this.payloadFactory = payloadFactory;
        this.ycloud = ycloud;
        this.meters = meters;
        this.limiter=limiter;
        meters.gauge("qc_meta_worker_in_flight", inFlight);
    }

    public void handle(String messageId) throws InterruptedException {
        if (!ycloudSettings.ready()) {
            throw new IllegalStateException("YCloud 配置尚未就绪");
        }
        acquireSlot();
        try {
            MessageRecord candidate=messages.get(messageId);
            if(java.util.Set.of("PENDING","RETRY_WAIT").contains(candidate.state())) {
                limiter.await(candidate.senderPhone(), config.senderRate());
            } else {
                meters.counter("qc_meta_kafka_consume_total", "result", "skipped").increment();
                return;
            }
            Instant lockedUntil = Instant.now().plus(config.claimTimeout());
            // 先用 MySQL 状态领取消息，Kafka 的重复消息会在这里被挡住。
            ClaimResult claim = messages.claimMessage(messageId, owner, lockedUntil);
            if (!claim.claimed()) {
                meters.counter("qc_meta_kafka_consume_total", "result", "skipped").increment();
                return;
            }
            meters.counter("qc_meta_kafka_consume_total", "result", "claimed").increment();
            submit(claim.message());
        } catch (RuntimeException exception) {
            meters.counter("qc_meta_kafka_consume_total", "result", "database_error").increment();
            throw exception;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void submit(MessageRecord message) {
        Instant started = Instant.now();
        String attemptId = UUID.randomUUID().toString();
        com.fasterxml.jackson.databind.JsonNode payload;
        try {
            payload = payloadFactory.create(message);
        } catch(RuntimeException exception) {
            // 此处只转换已保存的请求，没有外部依赖；坏数据重试也不会恢复。
            failLocally(message,attemptId,started,"INVALID_SAVED_PAYLOAD","已保存的发送内容无法转换，请核对原请求");
            return;
        }
        YCloudClient.SendResult result;
        try {
            result = ycloud.send(payload, message.deliveryMode().equals("DIRECT"));
        } catch (YCloudException exception) {
            Instant finished = Instant.now();
            messages.recordAttempt(new AttemptRecord(attemptId, message.messageId(), started, finished,
                    exception.kind().name(), exception.httpStatus(), exception.requestId(),
                    exception.code(), exception.getMessage()));
            switch (exception.kind()) {
                // 三类结果必须分开处理，UNKNOWN 绝不能走自动重试。
                case RETRYABLE -> retryOrFail(message, exception, finished);
                case PERMANENT -> messages.markFailed(message.messageId(), exception.code(),
                        exception.getMessage(), finished);
                case UNKNOWN -> messages.markUnknown(message.messageId(), exception.code(), exception.getMessage());
            }
            log.warn("消息没有被 YCloud 明确受理，messageId={}，result={}，code={}，httpStatus={}",
                    message.messageId(), exception.kind(), exception.code(), exception.httpStatus());
            return;
        } catch (RuntimeException exception) {
            messages.markUnknown(message.messageId(),"UNEXPECTED_SEND_ERROR","调用发送时未拿到明确结果，请核对原消息");
            return;
        }
        // 发送后的数据库异常向外抛出，不能当成发送前校验失败，更不能触发再次发送。
        Instant finished=Instant.now();
        messages.applyProviderResult(message.messageId(),result.id(),result.wamid(),
                result.status().isBlank()?"ACCEPTED":result.status(),result.statusTime(),result.errorCode());
        messages.recordAttempt(new AttemptRecord(attemptId,message.messageId(),started,finished,
                result.status().isBlank()?"ACCEPTED":result.status().toUpperCase(java.util.Locale.ROOT),200,result.requestId(),result.errorCode(),null));
    }

    private void retryOrFail(MessageRecord message, YCloudException exception, Instant finished) {
        int maxRetries = config.maxSafeRetries();
        if (message.attemptCount() >= maxRetries) {
            messages.markFailed(message.messageId(), exception.code(),
                    "达到 " + maxRetries + " 次安全重试上限: " + exception.getMessage(), finished);
            return;
        }
        Duration delay = OutboxPublisher.retryDelay(message.attemptCount());
        if (exception.retryAfter().compareTo(delay) > 0) {
            delay = exception.retryAfter();
        }
        messages.markRetry(message.messageId(), exception.code(), exception.getMessage(), finished.plus(delay));
    }

    private void failLocally(MessageRecord message, String attemptId, Instant started,
                             String code, String error) {
        Instant finished = Instant.now();
        messages.recordAttempt(new AttemptRecord(attemptId, message.messageId(), started, finished,
                "FAILED", null, null, code, error));
        messages.markFailed(message.messageId(), code, error, finished);
    }

    private void acquireSlot() throws InterruptedException {
        while (true) {
            int limit = config.workerCount();
            int current = inFlight.incrementAndGet();
            if (current <= limit) {
                return;
            }
            inFlight.decrementAndGet();
            Thread.sleep(20);
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void recoverInterruptedMessages() {
        int count = messages.recoverExpiredMessageClaims();
        if (count > 0) {
            log.warn("发现结果不明的历史发送任务，count={}", count);
        }
    }
}
