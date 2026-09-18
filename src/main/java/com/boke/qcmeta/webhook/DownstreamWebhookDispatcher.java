package com.boke.qcmeta.webhook;

import com.boke.qcmeta.config.DownstreamProperties;
import com.boke.qcmeta.mapper.EventMapper;
import com.boke.qcmeta.model.db.EventRows.Callback;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 单实例顺序推送已验签并持久化的回调，网络等待不占用数据库事务。 */
@Component
public class DownstreamWebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(DownstreamWebhookDispatcher.class);
    private final EventMapper events;
    private final DownstreamProperties config;
    private final MeterRegistry meters;
    private final HttpClient client;

    public DownstreamWebhookDispatcher(EventMapper events, DownstreamProperties config, MeterRegistry meters) {
        this.events = events;
        this.config = config;
        this.meters = meters;
        this.client = HttpClient.newBuilder().connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @jakarta.annotation.PreDestroy
    void close() { client.shutdownNow(); }

    @Scheduled(fixedDelay = 1000)
    void forwardDue() {
        if (!config.ready()) return;
        for (Callback event : events.pendingCallbacks()) {
            if (Thread.currentThread().isInterrupted()) return;
            boolean accepted = false;
            try {
                var request = HttpRequest.newBuilder(URI.create(config.callbackUrl()))
                        .timeout(config.requestTimeout()).header("Content-Type", "application/json")
                        .header("X-Qc-Delivery-Attempt", Long.toString((long) event.attempts() + 1))
                        .POST(HttpRequest.BodyPublishers.ofString(event.payload().toString(), StandardCharsets.UTF_8)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.discarding());
                accepted = response.statusCode() >= 200 && response.statusCode() < 300;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                // 不记录请求、响应和回调地址，防止客户正文或地址中的敏感信息进入日志。
                log.warn("下游回调请求失败，exceptionType={}", failure.getClass().getSimpleName());
            }
            // 下游已接收但本地确认失败时会重复推送，下游必须按原始事件 id 去重。
            if (accepted) events.markForwarded(event.eventId());
            else events.deferCallback(event.eventId(), Instant.now().plusSeconds(
                    Math.min(300, 5L << Math.min(event.attempts(), 6))));
            meters.counter("qc_meta_webhook_forward_total", "result", accepted ? "accepted" : "retry").increment();
        }
    }
}
