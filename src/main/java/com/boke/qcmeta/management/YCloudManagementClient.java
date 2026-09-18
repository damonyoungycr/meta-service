package com.boke.qcmeta.management;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.config.YCloudProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class YCloudManagementClient {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final YCloudProperties runtime;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @org.springframework.beans.factory.annotation.Autowired
    public YCloudManagementClient(YCloudProperties runtime, ObjectMapper mapper) {
        this(runtime,mapper,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    YCloudManagementClient(YCloudProperties runtime,ObjectMapper mapper,HttpClient http) {
        this.runtime = runtime;
        this.mapper = mapper;
        this.http = http;
    }

    public JsonNode get(String path, Map<String, String> query) {
        String suffix = query.isEmpty() ? "" : "?" + query.entrySet().stream()
                .map(e -> segment(e.getKey()) + "=" + segment(e.getValue())).collect(Collectors.joining("&"));
        return request("GET", path + suffix, null);
    }

    public JsonNode request(String method, String path, JsonNode payload) {
        var config = runtime;
        if (config.apiKey().isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置 YCloud API Key");
        }
        var builder = HttpRequest.newBuilder(URI.create(config.baseUrl()
                        .replaceAll("/+$", "") + "/v2/whatsapp" + path))
                .timeout(config.requestTimeout())
                .header("X-API-Key", config.apiKey())
                .header("Accept", "application/json");
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        if (payload != null) builder.header("Content-Type", "application/json");
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(builder.build(), info -> new LimitedBody());
        try {
            // deadline 包含整个响应体；只给 send/headers 设置 timeout 不能中止挂起的 body 读取。
            var response = pending.get(config.requestTimeout().toMillis(),TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                // 上游错误正文可能带业务数据；对外只保留可判断的 HTTP 结果。
                if (status < 200 || status >= 300) {
                    HttpStatus result = switch (status) {
                        case 400, 422 -> HttpStatus.BAD_REQUEST;
                        case 404 -> HttpStatus.NOT_FOUND;
                        case 409 -> HttpStatus.CONFLICT;
                        case 429 -> HttpStatus.TOO_MANY_REQUESTS;
                        default -> HttpStatus.BAD_GATEWAY;
                    };
                    throw new ApiException(result, "YCloud 管理请求未成功（HTTP " + status + "），请查询资源后再决定是否重试");
                }
                byte[] bytes = response.body();
                JsonNode result = bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes);
                if (result == null || (!result.isObject() && !result.isArray())) throw new IOException("invalid response");
                return result;
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw uncertain();
        } catch (IOException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            pending.cancel(true);
            throw uncertain();
        }
    }

    private ApiException uncertain() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "未获得 YCloud 管理请求的明确响应；修改操作可能已生效，请先查询确认，服务不会自动重复提交");
    }

    public static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks){
            for(ByteBuffer chunk:chunks){
                if((long)bytes.size()+chunk.remaining()>MAX_BYTES){
                    subscription.cancel();result.completeExceptionally(new IOException("response too large"));return;
                }
                byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable exception){result.completeExceptionally(exception);}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
