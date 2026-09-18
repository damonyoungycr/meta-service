package com.boke.qcmeta.media;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.boke.qcmeta.api.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class OssStorage {
    private final AssetProperties properties;
    private OSS client;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    @Autowired
    public OssStorage(AssetProperties properties) { this.properties = properties; }
    OssStorage(AssetProperties properties, OSS client) { this.properties=properties; this.client=client; }
    public void requireConfigured() {
        if (properties.getAccessKeyId().isBlank() || properties.getAccessKeySecret().isBlank()
                || properties.getBucketName().isBlank())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OSS 配置不完整，请检查 oss.cla");
        requireHttps(properties.getEndpoint()); requireHttps(properties.getPreviewImg());
    }
    static void requireHttps(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("存储或入口地址必须使用有效 HTTPS 地址");
        }
    }
    private synchronized OSS client() {
        requireConfigured();
        if (client == null) {
            ClientBuilderConfiguration config = new ClientBuilderConfiguration();
            config.setConnectionTimeout(10000); config.setSocketTimeout(30000); config.setMaxErrorRetry(1);
            client = new OSSClientBuilder().build(properties.getEndpoint(), properties.getAccessKeyId(),
                    properties.getAccessKeySecret(), config);
        }
        return client;
    }
    public StoredObject put(byte[] bytes, String mime, int directoryType) {
        MediaRules.validate(bytes, mime, properties.getMaxBytes());
        String path = legacyPath(directoryType, Instant.now()) + "." + MediaRules.extension(mime);
        String key = properties.getImgKey() + path;
        ObjectMetadata metadata = new ObjectMetadata();
        // 使用真实字节数；InputStream.available() 不能表示文件长度。
        metadata.setContentLength(bytes.length); metadata.setContentType(MediaRules.normalize(mime));
        metadata.setCacheControl("no-cache"); metadata.setHeader("Pragma", "no-cache");
        metadata.setContentDisposition((mime.startsWith("image/") ? "inline" : "attachment") + ";filename=\"" + path.substring(path.lastIndexOf('/')+1) + "\"");
        try (ByteArrayInputStream stream = new ByteArrayInputStream(bytes)) {
            client().putObject(properties.getBucketName(), key, stream, metadata);
        } catch (ApiException exception) { throw exception; }
        catch (RuntimeException | IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSS 上传失败，可重新上传");
        }
        return new StoredObject(key, properties.getPreviewImg() + path);
    }
    public byte[] read(String key, long limit) {
        try (OSSObject object = client().getObject(properties.getBucketName(), key)) {
            byte[] bytes = object.getObjectContent().readNBytes((int)Math.min(limit + 1, Integer.MAX_VALUE));
            if (bytes.length > limit) throw ApiException.badRequest("OSS 文件超过允许大小");
            return bytes;
        } catch (ApiException exception) { throw exception; }
        catch (IOException | RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSS 文件读取失败");
        }
    }
    public static String legacyPath(int type, Instant now) {
        String prefix = switch(type) {
            case 1 -> "kefu/config"; case 2 -> "kefu/chat"; case 3 -> "player/chat";
            case 4 -> "player/fb"; case 5 -> "player/mail"; case 6 -> "robot/config";
            case 7 -> "robot/repository"; default -> "other";
        };
        StringBuilder suffix = new StringBuilder();
        for (int i=0; i<6; i++) suffix.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return prefix + "/" + DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(now)
                + "/" + now.getEpochSecond() + "-" + suffix;
    }
    @PreDestroy void close() { if (client != null) client.shutdown(); }
    public record StoredObject(String key, String url) {}
}
