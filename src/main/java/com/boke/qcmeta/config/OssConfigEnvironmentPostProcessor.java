package com.boke.qcmeta.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class OssConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final Map<String, String> FIELDS = Map.of(
            "endpoint", "endpoint", "accesskeyid", "access-key-id",
            "accesskeysecret", "access-key-secret", "bucketname", "bucket-name",
            "imgkey", "img-key", "previewimg", "preview-img",
            "maxbytes", "max-bytes");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String fileName = environment.getProperty("oss.file-name", "");
        Properties source = ExternalConfigFile.read(fileName, true, "OSS 加密配置 oss.file-name 指向的 oss.cla 文件");

        Properties assets = new Properties();
        for (String name : source.stringPropertyNames()) {
            String field;
            if (name.startsWith("oss.config.")) field = name.substring("oss.config.".length());
            else if (name.startsWith("qc.assets.")) field = name.substring("qc.assets.".length());
            else continue;
            String normalized = field.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
            String target = FIELDS.get(normalized);
            // 只装载 OSS 字段，避免旧文件中的其他参数覆盖数据库等服务配置。
            if (target == null) continue;
            String key = "qc.assets." + target;
            String value = ExternalConfigFile.resolve(environment, source.getProperty(name), "OSS 配置");
            String previous = (String) assets.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException("OSS 配置包含重复且不一致的字段：" + target);
            }
        }
        for (String required : new String[]{"endpoint", "access-key-id", "access-key-secret", "bucket-name", "preview-img"}) {
            if (assets.getProperty("qc.assets." + required, "").isBlank()) {
                throw new IllegalStateException("OSS 加密配置缺少字段：" + required);
            }
        }
        requireHttps(assets.getProperty("qc.assets.endpoint"), "endpoint");
        requireHttps(assets.getProperty("qc.assets.preview-img"), "preview-img");
        if (assets.containsKey("qc.assets.max-bytes")) {
            try {
                if (Long.parseLong(assets.getProperty("qc.assets.max-bytes")) <= 0) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("OSS 配置 max-bytes 必须是正整数");
            }
        }
        // 沿用旧服务的优先级，以文件中的存储参数为准。
        environment.getPropertySources().addFirst(new PropertiesPropertySource("ossClaConfig", assets));
    }

    private static void requireHttps(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("OSS 配置 " + field + " 必须使用有效 HTTPS 地址");
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 2;
    }
}
