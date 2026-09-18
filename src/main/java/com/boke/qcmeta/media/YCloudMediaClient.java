package com.boke.qcmeta.media;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.config.YCloudProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

@Component
public class YCloudMediaClient {
    private final YCloudProperties config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    @Autowired
    public YCloudMediaClient(YCloudProperties config, ObjectMapper mapper) {
        this(config, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    YCloudMediaClient(YCloudProperties config, ObjectMapper mapper, HttpClient client) {
        this.config=config; this.mapper=mapper; this.client=client;
    }
    public byte[] download(String source, long maxBytes) {
        URI uri = validateDownloadUri(source);
        var snapshot = config;
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(snapshot.requestTimeout())
                .header("X-API-Key", snapshot.apiKey()).GET().build();
        return execute(request, maxBytes, snapshot.requestTimeout()).body();
    }
    public String upload(String phone, byte[] bytes, String filename, String mime) {
        var snapshot = config;
        String boundary = "qc-" + UUID.randomUUID();
        byte[] start = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + MediaRules.safeFilename(filename, mime) + "\"\r\nContent-Type: " + mime + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] end = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(snapshot.baseUrl().replaceAll("/+$", "")
                + "/v2/whatsapp/media/" + URLEncoder.encode(phone, StandardCharsets.UTF_8) + "/upload");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(snapshot.requestTimeout())
                .header("X-API-Key", snapshot.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(start),
                        HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(end))).build();
        try {
            String id = mapper.readTree(execute(request, 2 * 1024 * 1024,
                    snapshot.requestTimeout()).body()).path("id").asText("");
            if (id.isBlank()) throw new IllegalStateException();
            return id;
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw new ApiException(HttpStatus.BAD_GATEWAY, "YCloud 媒体上传响应缺少有效 id"); }
    }
    public static URI validateDownloadUri(String source) {
        try {
            URI uri = URI.create(source);
            // 只向官方媒体下载地址发送 API Key，禁止重定向和自定义远程文件 URL。
            if (!"https".equals(uri.getScheme()) || !"api.ycloud.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null || uri.getFragment()!=null
                    || !uri.getRawPath().matches("/v2/whatsapp/media/download/[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException exception) { throw ApiException.badRequest("仅允许下载 YCloud 官方入站媒体地址"); }
    }
    private HttpResponse<byte[]> execute(HttpRequest request, long limit, Duration timeout) {
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info -> new LimitedBody(limit));
        try {
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode()<200 || response.statusCode()>=300)
                throw new ApiException(HttpStatus.BAD_GATEWAY, "YCloud 媒体请求失败（HTTP " + response.statusCode() + "）");
            return response;
        } catch (ApiException exception) { throw exception; }
        catch (InterruptedException exception) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "YCloud 媒体请求被中断");
        } catch (Exception exception) {
            future.cancel(true);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "YCloud 媒体请求超时、响应过大或下载失败");
        }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(long limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if ((long)bytes.size()+chunk.remaining()>limit) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("媒体响应过大")); return;
                }
                byte[] part = new byte[chunk.remaining()]; chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
