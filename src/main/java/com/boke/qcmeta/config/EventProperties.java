package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("events")
public record EventProperties(@DefaultValue("30") int retentionDays) {
    public EventProperties {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("events.retention-days 必须大于 0");
        }
    }
}
