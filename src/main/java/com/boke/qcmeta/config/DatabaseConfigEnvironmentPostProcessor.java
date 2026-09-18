package com.boke.qcmeta.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import java.util.Properties;
import java.util.Set;

public final class DatabaseConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final Set<String> ENVIRONMENTS = Set.of("dev", "beta", "dx", "prod");
    private static final Set<String> FIELDS = Set.of("url", "username", "password",
            "hikari.maximum-pool-size", "hikari.minimum-idle", "hikari.connection-timeout");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) profiles = environment.getDefaultProfiles();
        // 混用两个环境会把后加载的部分参数覆盖到前一个环境，直接拒绝更容易发现部署错误。
        if (profiles.length != 1 || !ENVIRONMENTS.contains(profiles[0])) {
            throw new IllegalStateException("必须且只能选择一个环境：dev、beta、dx、prod");
        }
        String fileName = environment.getProperty("spring.datasource.file-name", "");
        boolean encrypted = fileName.endsWith(".cla");
        boolean projectEnvironment = profiles[0].equals("dev") || profiles[0].equals("beta");
        if (!encrypted && (!projectEnvironment || !fileName.endsWith(".properties"))) {
            throw new IllegalStateException("dev、beta 数据库配置使用 .properties 或 .cla；dx、prod 必须使用 init.cla 加密文件");
        }
        Properties source = ExternalConfigFile.read(fileName, encrypted, "数据库配置 spring.datasource.file-name");
        Properties database = new Properties();
        for (String field : FIELDS) {
            String key = "spring.datasource." + field;
            if (source.containsKey(key)) database.setProperty(key,
                    ExternalConfigFile.resolve(environment, source.getProperty(key), "数据库配置"));
        }
        for (String field : new String[]{"url", "username", "password"}) {
            if (database.getProperty("spring.datasource." + field, "").isBlank()) {
                throw new IllegalStateException("数据库配置缺少字段：spring.datasource." + field);
            }
        }
        if (!database.getProperty("spring.datasource.url").startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("数据库配置必须使用 MySQL JDBC 地址");
        }
        requireNumber(database, "maximum-pool-size", 1, "20");
        requireNumber(database, "minimum-idle", 0, "2");
        requireNumber(database, "connection-timeout", 250, "10000");
        if (Long.parseLong(database.getProperty("spring.datasource.hikari.minimum-idle"))
                > Long.parseLong(database.getProperty("spring.datasource.hikari.maximum-pool-size"))) {
            throw new IllegalStateException("数据库 minimum-idle 不能大于 maximum-pool-size");
        }
        // 只加载本服务的 MySQL 字段，不把其他项目的配置或环境选择写入 Spring。
        environment.getPropertySources().addFirst(new PropertiesPropertySource("databaseFileConfig", database));
    }

    private static void requireNumber(Properties database, String field, long minimum, String fallback) {
        String key = "spring.datasource.hikari." + field;
        String value = database.getProperty(key, fallback);
        try {
            long number = Long.parseLong(value);
            if (number < minimum || number > Integer.MAX_VALUE) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("数据库连接池配置无效：" + field);
        }
        database.setProperty(key, value);
    }

    @Override
    public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
}
