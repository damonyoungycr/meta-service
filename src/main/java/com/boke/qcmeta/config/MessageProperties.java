package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("message")
public record MessageProperties(
        @DefaultValue("4") int workerCount,
        @DefaultValue("10") int senderRate,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("2m") Duration claimTimeout,
        @DefaultValue("5") int maxSafeRetries) {
    public MessageProperties {
        if (workerCount < 1 || senderRate < 1 || senderRate > 1000 || maxSafeRetries < 1) {
            throw new IllegalArgumentException("message.worker-count、max-safe-retries 必须大于 0，sender-rate 必须为 1 到 1000");
        }
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(1)) < 0
                || claimTimeout == null || claimTimeout.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("message.poll-interval、claim-timeout 必须至少为 1ms");
        }
    }

    public void validateRequestTimeout(Duration requestTimeout) {
        // 锁必须覆盖一次完整请求，避免发送尚未结束就被恢复任务判为结果不明。
        if (claimTimeout.compareTo(requestTimeout.plusSeconds(5)) < 0) {
            throw new IllegalArgumentException("message.claim-timeout 至少要比 ycloud.request-timeout 多 5 秒");
        }
    }
}
