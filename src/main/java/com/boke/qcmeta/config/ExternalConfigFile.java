package com.boke.qcmeta.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ExternalConfigFile {
    private ExternalConfigFile() {}

    static Properties read(String fileName, boolean encrypted, String description) {
        try {
            if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException();
            String content = withoutBom(readText(fileName));
            if (encrypted) content = ClaUtil.decrypt(content.strip());
            Properties properties = new Properties();
            properties.load(new StringReader(withoutBom(content)));
            return properties;
        } catch (IOException | IllegalArgumentException exception) {
            // 文件或解密错误可能含有凭据，不向日志传递原内容或底层异常。
            throw new IllegalStateException("无法加载" + description + "，请检查文件路径、权限和格式");
        }
    }

    private static String readText(String fileName) throws IOException {
        if (fileName.startsWith("classpath:")) return readClasspath(fileName.substring("classpath:".length()));
        if (fileName.startsWith("file:")) return Files.readString(Path.of(URI.create(fileName)), StandardCharsets.UTF_8);
        Path path = Path.of(fileName);
        // 部署时可用工作目录中的同名文件覆盖；本地开发和打包运行读取 resources。
        if (path.isAbsolute() || Files.exists(path)) return Files.readString(path, StandardCharsets.UTF_8);
        return readClasspath(fileName);
    }

    private static String readClasspath(String name) throws IOException {
        // 使用流才能读取 Spring Boot JAR 内的资源，不能将其当成磁盘路径。
        try (var input = new ClassPathResource(name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String resolve(ConfigurableEnvironment environment, String value, String description) {
        try {
            return environment.resolveRequiredPlaceholders(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(description + "引用的环境变量缺失或无效");
        }
    }

    private static String withoutBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
}
