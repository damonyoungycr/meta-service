package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("ycloud")
public record YCloudProperties(String baseUrl, String apiKey, String webhookSecret, Duration requestTimeout) {
    public YCloudProperties {
        baseUrl = baseUrl == null ? "https://api.ycloud.com" : baseUrl.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(15) : requestTimeout;
        try {
            URI uri = URI.create(baseUrl);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("ycloud.base-url 必须是有效 HTTP 或 HTTPS 地址");
        }
        if (requestTimeout.compareTo(Duration.ofMillis(1)) < 0
                || requestTimeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("ycloud.request-timeout 必须在 1ms 到 1d 之间");
        }
    }

    public boolean ready() {
        return !apiKey.isBlank() && !webhookSecret.isBlank();
    }

    // 配置直接从 YML 读取，意外打印配置对象时也不能泄露凭据。
    @Override
    public String toString() {
        return "YCloudProperties[credentials=REDACTED]";
    }
}
