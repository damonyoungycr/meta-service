package com.boke.qcmeta.message;

import com.boke.qcmeta.config.MessageProperties;
import com.boke.qcmeta.config.YCloudProperties;
import com.boke.qcmeta.model.StoreModels.OutboxRecord;
import com.boke.qcmeta.queue.KafkaClient;
import com.boke.qcmeta.store.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 把 MySQL outbox 中到期的任务发布到 Kafka。
 *
 * <p>Kafka 中只保存 {@code messageId}，手机号和正文仍只放在 MySQL。发布失败时记录会回到 NEW，
 * 并逐步延长重试间隔；发布进程意外退出后，超时的锁也会被定时恢复。</p>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final MessageRepository messages;
    private final MessageProperties config;
    private final YCloudProperties ycloud;
    private final KafkaClient queue;
    private final MeterRegistry meters;
    private final String owner = UUID.randomUUID().toString();

    public OutboxPublisher(MessageRepository messages, MessageProperties config,
                           YCloudProperties ycloud, KafkaClient queue, MeterRegistry meters) {
        this.messages = messages;
        this.config = config;
        this.ycloud = ycloud;
        this.queue = queue;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${message.poll-interval:500ms}")
    void publishReadyMessages() {
        Instant now = Instant.now();
        if (!ycloud.ready() || !queue.ready()) {
            return;
        }
        int batchSize = Math.max(10, Math.min(25, config.workerCount()) * 4);
        List<OutboxRecord> records;
        try {
            records = messages.claimOutbox(owner, batchSize, now.plusSeconds(30));
        } catch (RuntimeException exception) {
            log.error("领取 Kafka 投递记录失败", exception);
            return;
        }
        for (OutboxRecord record : records) {
            Timer.Sample sample = Timer.start(meters);
            try {
                queue.publish(record.messageId());
                messages.markOutboxPublished(record.outboxId(), owner);
                meters.counter("qc_meta_kafka_publish_total", "result", "success").increment();
            } catch (Exception exception) {
                Duration delay = retryDelay(record.publishAttempts() + 1);
                messages.markOutboxRetry(record.outboxId(), owner, exception.getMessage(), Instant.now().plus(delay));
                meters.counter("qc_meta_kafka_publish_total", "result", "failed").increment();
                log.warn("发布 Kafka 消息失败，messageId={}，稍后重试", record.messageId(), exception);
            } finally {
                sample.stop(meters.timer("qc_meta_kafka_publish_duration_seconds"));
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    void recoverExpiredClaims() {
        int count = messages.recoverExpiredOutboxClaims();
        if (count > 0) {
            log.warn("已恢复中断的 Kafka 发布任务，count={}", count);
        }
    }

    static Duration retryDelay(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        long seconds = Math.min(300, 1L << Math.min(safeAttempt - 1, 8));
        return Duration.ofSeconds(seconds).plusMillis((safeAttempt % 5L) * 200L);
    }
}
