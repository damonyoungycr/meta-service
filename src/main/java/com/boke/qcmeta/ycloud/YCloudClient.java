package com.boke.qcmeta.ycloud;

import com.boke.qcmeta.config.YCloudProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用 JDK 21 自带的 HTTP 客户端调用 YCloud。
 *
 * <p>这个类只负责发送请求和判断结果，不记录 API Key、消息正文或 YCloud 响应正文。错误会被分成
 * 可安全重试、明确失败和结果不明三类，让发送任务选择正确的后续动作。</p>
 */
@Component
public class YCloudClient {

    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final YCloudProperties runtimeConfig;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final HttpClient httpClient;

    public YCloudClient(YCloudProperties runtimeConfig, ObjectMapper objectMapper,
                        MeterRegistry meters) {
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    public SendResult send(JsonNode payload, boolean direct) {
        String path = direct ? "/v2/whatsapp/messages/sendDirectly" : "/v2/whatsapp/messages";
        return request("POST", path, payload);
    }

    public SendResult getMessage(String providerMessageId) {
        if(providerMessageId==null || !providerMessageId.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException("YCloud 消息编号格式无效");
        return request("GET", "/v2/whatsapp/messages/" + providerMessageId, null);
    }

    private SendResult request(String method, String path, JsonNode payload) {
        YCloudProperties config = runtimeConfig;
        String baseUrl = config.baseUrl().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(config.requestTimeout())
                .header("X-API-Key", config.apiKey())
                .header("Accept", "application/json");
        if (payload == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8));
        }

        Timer.Sample sample = Timer.start(meters);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            // 从接收流上限制内存，总等待时间还覆盖响应体缓慢传输。
            pending = httpClient.sendAsync(builder.build(), info -> new LimitedResponse(info.statusCode()));
            HttpResponse<byte[]> response = pending.get(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw parseError(response);
            }
            JsonNode body = response.body().length == 0
                    ? objectMapper.createObjectNode() : objectMapper.readTree(response.body());
            String id = body.path("id").asText("");
            if (id.isBlank()) {
                throw new YCloudException(ErrorKind.UNKNOWN, response.statusCode(),
                        "INVALID_RESPONSE", "YCloud 成功响应缺少消息 id，无法确认是否已经受理",
                        requestId(response), Duration.ZERO, null);
            }
            String state=body.path("status").asText("").toUpperCase(java.util.Locale.ROOT);
            if(!java.util.Set.of("ACCEPTED","SENT","DELIVERED","READ","FAILED").contains(state))
                throw new YCloudException(ErrorKind.UNKNOWN,response.statusCode(),"INVALID_STATUS","YCloud 响应缺少可识别的消息状态",requestId(response),Duration.ZERO,null);
            count("accepted", response.statusCode());
            return new SendResult(id, body.path("wamid").asText(""),
                    state, requestId(response),safeCode(body.path("errorCode").asText("")),statusTime(body,state));
        } catch (YCloudException exception) {
            count(exception.kind().name().toLowerCase(), exception.httpStatus());
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            count("unknown", 0);
            throw new YCloudException(ErrorKind.UNKNOWN, 0, "INTERRUPTED",
                    "调用 YCloud 时线程被中断", "", Duration.ZERO, null);
        } catch (TimeoutException exception) {
            count("unknown",0);
            throw new YCloudException(ErrorKind.UNKNOWN,0,"REQUEST_TIMEOUT","YCloud 请求或响应体接收超时","",Duration.ZERO,null);
        } catch (ExecutionException exception) {
            Throwable cause=exception.getCause();
            while(cause!=null && !(cause instanceof ResponseTooLarge) && cause.getCause()!=null) cause=cause.getCause();
            if(cause instanceof ResponseTooLarge tooLarge) {
                count("unknown",tooLarge.httpStatus);
                throw new YCloudException(ErrorKind.UNKNOWN,tooLarge.httpStatus,"RESPONSE_TOO_LARGE","YCloud 响应超过 2MB","",Duration.ZERO,null);
            }
            count("unknown",0);
            throw new YCloudException(ErrorKind.UNKNOWN,0,"NETWORK_ERROR","没有拿到 YCloud 的明确响应","",Duration.ZERO,null);
        } catch (IOException | RuntimeException exception) {
            count("unknown", 0);
            // JSON 解析异常可能含响应片段，禁止沿异常原因链进入日志。
            throw new YCloudException(ErrorKind.UNKNOWN, 0, "NETWORK_ERROR",
                    "没有拿到 YCloud 的明确响应", "", Duration.ZERO, null);
        } finally {
            if(pending!=null && !pending.isDone()) pending.cancel(true);
            sample.stop(meters.timer("qc_meta_ycloud_request_duration_seconds", "operation", "send"));
        }
    }

    private YCloudException parseError(HttpResponse<byte[]> response) {
        String code = "HTTP_" + response.statusCode();
        String message = "YCloud 请求失败";
        String bodyRequestId = "";
        try {
            JsonNode body = objectMapper.readTree(response.body());
            if(body==null) body=objectMapper.createObjectNode();
            JsonNode error = body.path("error");
            code = text(error, "code", text(body, "code", code));
            message = "YCloud 请求失败，请按错误码核对";
            bodyRequestId = safeRequestId(text(error, "requestId", text(body, "requestId", "")));
        } catch (IOException ignored) {
            // 非 JSON 错误仍按 HTTP 状态处理，不把响应正文写入日志。
        }
        code=safeCode(code);
        ErrorKind kind = response.statusCode() == 429 ? ErrorKind.RETRYABLE
                : response.statusCode() >= 500 || response.statusCode()==408 || response.statusCode()<400 ? ErrorKind.UNKNOWN : ErrorKind.PERMANENT;
        String requestId = requestId(response);
        if (requestId.isBlank()) {
            requestId = bodyRequestId;
        }
        return new YCloudException(kind, response.statusCode(), code, message,
                requestId, retryAfter(response), null);
    }

    private Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isBlank()) {
            return Duration.ZERO;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
        } catch (NumberFormatException ignored) {
            try {
                return Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
            } catch (DateTimeParseException exception) {
                return Duration.ZERO;
            }
        }
    }

    private String requestId(HttpResponse<?> response) {
        for (String name : new String[]{"YCloud-Request-ID", "X-Request-ID", "Request-ID"}) {
            String value = response.headers().firstValue(name).orElse("");
            if (!value.isBlank()) {
                return safeRequestId(value);
            }
        }
        return "";
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private void count(String result, int status) {
        meters.counter("qc_meta_ycloud_requests_total", "operation", "send",
                "result", result, "http_status", Integer.toString(status)).increment();
    }

    private static String safeCode(String value) { return value.matches("[A-Za-z0-9_.:-]{0,128}")?value:"PROVIDER_ERROR"; }
    private static String safeRequestId(String value) { return value.matches("[A-Za-z0-9_.:-]{1,255}")?value:""; }

    private static Instant statusTime(JsonNode body,String state) {
        String field=switch(state) {
            case "SENT" -> "sendTime"; case "DELIVERED" -> "deliverTime";
            case "READ" -> "readTime"; case "FAILED" -> "updateTime"; default -> "";
        };
        if(field.isEmpty()) return null;
        String value=body.path(field).asText("");
        if(value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch(DateTimeParseException exception) { return null; }
    }

    public record SendResult(String id, String wamid, String status, String requestId,String errorCode,Instant statusTime) {
        public SendResult(String id,String wamid,String status,String requestId,String errorCode) { this(id,wamid,status,requestId,errorCode,null); }
        public SendResult(String id,String wamid,String status,String requestId) { this(id,wamid,status,requestId,"",null); }
    }

    private static final class ResponseTooLarge extends IOException {
        private final int httpStatus;
        ResponseTooLarge(int httpStatus) { super("YCloud 响应超过限制"); this.httpStatus=httpStatus; }
    }

    private static final class LimitedResponse implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final int httpStatus;
        private Flow.Subscription subscription;
        LimitedResponse(int httpStatus) {this.httpStatus=httpStatus;}
        public CompletionStage<byte[]> getBody() {return result;}
        public void onSubscribe(Flow.Subscription subscription) {this.subscription=subscription;subscription.request(1);}
        public void onNext(List<ByteBuffer> chunks) {
            for(ByteBuffer chunk:chunks) {
                if((long)bytes.size()+chunk.remaining()>MAX_RESPONSE_BYTES) {
                    result.completeExceptionally(new ResponseTooLarge(httpStatus));subscription.cancel();return;
                }
                byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) {result.completeExceptionally(error);}
        public void onComplete() {result.complete(bytes.toByteArray());}
    }

    public enum ErrorKind {
        PERMANENT, RETRYABLE, UNKNOWN
    }

    public static class YCloudException extends RuntimeException {
        private final ErrorKind kind;
        private final int httpStatus;
        private final String code;
        private final String requestId;
        private final Duration retryAfter;

        public YCloudException(ErrorKind kind, int httpStatus, String code, String message,
                               String requestId, Duration retryAfter, Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.httpStatus = httpStatus;
            this.code = code;
            this.requestId = requestId;
            this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
        }

        public ErrorKind kind() { return kind; }
        public int httpStatus() { return httpStatus; }
        public String code() { return code; }
        public String requestId() { return requestId; }
        public Duration retryAfter() { return retryAfter; }
    }
}
