package com.boke.qcmeta.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("qc")
public record QcProperties(
        int grpcPort,
        Duration shutdownTimeout,
        Kafka kafka
) {
    public QcProperties {
        shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(15) : shutdownTimeout;
        kafka = kafka == null ? new Kafka(true, "127.0.0.1:9092",
                "qc-meta-message-send-v1", "qc-meta-producer", "qc-meta-sender", 4) : kafka;
    }

    public record Kafka(
            boolean enabled,
            String bootstrapServers,
            String topic,
            String producerClientId,
            String consumerGroup,
            int concurrency
    ) {
        public Kafka {
            bootstrapServers = bootstrapServers == null ? "" : bootstrapServers.trim();
            topic = topic == null ? "" : topic.trim();
            producerClientId = producerClientId == null ? "" : producerClientId.trim();
            consumerGroup = consumerGroup == null ? "" : consumerGroup.trim();
        }
    }
}
