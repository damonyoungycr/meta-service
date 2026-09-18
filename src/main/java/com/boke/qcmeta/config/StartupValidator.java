package com.boke.qcmeta.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class StartupValidator implements InitializingBean {

    private static final Pattern KAFKA_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private final QcProperties properties;

    public StartupValidator(QcProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.grpcPort() < 1 || properties.grpcPort() > 65535) {
            throw new IllegalStateException("qc.grpc-port 必须是 1 到 65535");
        }
        QcProperties.Kafka mq = properties.kafka();
        if (mq.enabled()) {
            if (mq.bootstrapServers().isBlank()) {
                throw new IllegalStateException("Kafka bootstrap-servers 不能为空");
            }
            requireMqValue(mq.topic(), "topic");
            requireMqValue(mq.producerClientId(), "producer-client-id");
            requireMqValue(mq.consumerGroup(), "consumer-group");
            if (mq.concurrency() < 1 || mq.concurrency() > 32) {
                throw new IllegalStateException("Kafka concurrency 必须是 1 到 32");
            }
        }
    }

    private void requireMqValue(String value, String name) {
        if (value.isBlank() || value.length() > 249 || value.equals(".") || value.equals("..")
                || !KAFKA_NAME.matcher(value).matches()) {
            throw new IllegalStateException("Kafka " + name + " 配置无效");
        }
    }
}
