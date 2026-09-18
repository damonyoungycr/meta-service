package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("downstream")
public record DownstreamProperties(@DefaultValue("") String callbackUrl,
                                   @DefaultValue("5s") Duration requestTimeout) {
    public DownstreamProperties {
        callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
        if (!callbackUrl.isEmpty()) {
            try {
                URI uri = URI.create(callbackUrl);
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("downstream.callback-url 必须为 HTTP 或 HTTPS 回调地址");
            }
        }
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofMillis(1)) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("downstream.request-timeout 必须在 1ms 到 30s 之间");
        }
    }
    public boolean ready() { return !callbackUrl.isBlank(); }
}
