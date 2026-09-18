package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("webhook")
public record WebhookProperties(
        @DefaultValue("5m") Duration signatureTolerance,
        @DefaultValue("2097152") int maxBodyBytes) {
    public WebhookProperties {
        if (signatureTolerance == null || signatureTolerance.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("webhook.signature-tolerance 必须至少为 1ms");
        }
        if (maxBodyBytes < 1 || maxBodyBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("webhook.max-body-bytes 必须在 1 到 2147483646 之间");
        }
    }
}
