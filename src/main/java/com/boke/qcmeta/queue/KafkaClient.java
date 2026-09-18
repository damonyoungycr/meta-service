package com.boke.qcmeta.queue;

import com.boke.qcmeta.config.QcProperties;
import com.boke.qcmeta.message.MessageWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kafka 只传 messageId；MySQL 状态负责拦截重复发送。
 * 业务处理完成后才提交消费位置，数据库暂时不可用时重新读取原记录。
 */
@Component
public class KafkaClient implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(KafkaClient.class);
    private static final Duration API_TIMEOUT = Duration.ofSeconds(5);
    private final QcProperties properties;
    private final MessageWorker worker;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final List<Consumer<String, String>> consumers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private Producer<String, String> producer;
    private Admin admin;

    public KafkaClient(QcProperties properties, MessageWorker worker, ObjectMapper objectMapper) {
        this.properties = properties;
        this.worker = worker;
        this.objectMapper = objectMapper;
    }

    @Override
    public synchronized void start() {
        if (!properties.kafka().enabled() || running.get()) return;
        QcProperties.Kafka config = properties.kafka();
        try {
            admin = Admin.create(connectionProperties());
            if (!topicAvailable()) throw new IllegalStateException("Kafka Topic 不存在或没有可用 Leader");
            Properties producerConfig = connectionProperties();
            producerConfig.put(ProducerConfig.CLIENT_ID_CONFIG, config.producerClientId());
            producerConfig.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producerConfig.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producerConfig.put(ProducerConfig.ACKS_CONFIG, "all");
            producerConfig.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
            producerConfig.put(ProducerConfig.LINGER_MS_CONFIG, 0);
            producerConfig.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000);
            producerConfig.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5000);
            producerConfig.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
            producer = new KafkaProducer<>(producerConfig);
            for (int i = 0; i < config.concurrency(); i++) {
                Properties consumerConfig = connectionProperties();
                consumerConfig.put(ConsumerConfig.GROUP_ID_CONFIG, config.consumerGroup());
                consumerConfig.put(ConsumerConfig.CLIENT_ID_CONFIG, config.consumerGroup() + "-" + i);
                consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
                consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
                consumerConfig.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
                consumerConfig.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
                // 每次只领一条，失败回退时不会跳过同批其他分区的记录。
                consumerConfig.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
                consumers.add(new KafkaConsumer<>(consumerConfig));
            }
            running.set(true);
            for (Consumer<String, String> consumer : consumers) {
                threads.add(Thread.ofVirtual().name("kafka-sender-" + threads.size())
                        .start(() -> consume(consumer)));
            }
            ready.set(true);
            log.info("Kafka 已连接，topic={}，consumers={}", config.topic(), consumers.size());
        } catch (Exception exception) {
            stop();
            throw new IllegalStateException("启动 Kafka 失败", exception);
        }
    }

    private Properties connectionProperties() {
        Properties config = new Properties();
        config.put("bootstrap.servers", properties.kafka().bootstrapServers());
        config.put("default.api.timeout.ms", 5000);
        config.put("request.timeout.ms", 5000);
        return config;
    }

    private void consume(Consumer<String, String> consumer) {
        try {
            consumer.subscribe(List.of(properties.kafka().topic()));
            while (running.get()) {
                try {
                    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                        if (!running.get()) break;
                        if (!processRecord(consumer, record)) Thread.sleep(1000);
                    }
                } catch (WakeupException exception) {
                    if (running.get()) throw exception;
                } catch (RuntimeException exception) {
                    ready.set(false);
                    log.warn("Kafka 消费暂时不可用，exceptionType={}", exception.getClass().getSimpleName());
                    Thread.sleep(1000);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            ready.set(false);
            consumer.close(API_TIMEOUT);
        }
    }

    boolean processRecord(Consumer<String, String> consumer, ConsumerRecord<String, String> record)
            throws InterruptedException {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        try {
            String messageId = readMessageId(record.value());
            if (messageId == null) {
                log.error("忽略无法识别的 Kafka 任务，partition={}，offset={}", record.partition(), record.offset());
            } else {
                worker.handle(messageId);
            }
            consumer.commitSync(Map.of(partition,
                    new OffsetAndMetadata(record.offset() + 1, record.leaderEpoch(), "")), API_TIMEOUT);
            return true;
        } catch (WakeupException | InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            // 只回退当前仍持有的分区；已转交的分区由新消费者从已提交位置恢复。
            if (consumer.assignment().contains(partition)) consumer.seek(partition, record.offset());
            log.warn("Kafka 任务稍后重试，partition={}，offset={}，exceptionType={}",
                    record.partition(), record.offset(), exception.getClass().getSimpleName());
            return false;
        }
    }

    private String readMessageId(String body) {
        if (body == null) return null;
        try {
            var envelope = objectMapper.readTree(body);
            var id = envelope == null ? null : envelope.get("messageId");
            return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText().trim() : null;
        } catch (JsonProcessingException exception) {
            // 不能打印解析异常，异常内容可能携带误入队列的客户正文。
            return null;
        }
    }

    public void publish(String messageId) throws Exception {
        if (!ready()) throw new IllegalStateException("Kafka 尚未就绪");
        String body = objectMapper.createObjectNode().put("messageId", messageId).toString();
        try {
            producer.send(new ProducerRecord<>(properties.kafka().topic(), messageId, body))
                    .get(6, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        }
    }

    @Scheduled(fixedDelay = 5000)
    synchronized void refreshHealth() {
        if (!running.get()) return;
        try {
            ready.set(threads.stream().allMatch(Thread::isAlive) && topicAvailable());
        } catch (Exception exception) {
            ready.set(false);
        }
    }

    private boolean topicAvailable() throws Exception {
        var topic = admin.describeTopics(List.of(properties.kafka().topic())).all()
                .get(5, TimeUnit.SECONDS).get(properties.kafka().topic());
        return !topic.partitions().isEmpty()
                && topic.partitions().stream().allMatch(p -> p.leader() != null && p.leader().id() >= 0);
    }

    public boolean ready() { return running.get() && ready.get(); }

    @Override
    public synchronized void stop() {
        ready.set(false);
        running.set(false);
        consumers.forEach(Consumer::wakeup);
        long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
        for (Thread thread : threads) {
            try {
                thread.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                if (thread.isAlive()) thread.interrupt();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                thread.interrupt();
            }
        }
        // 启动过程中失败时，尚未交给线程的消费者也需要关闭。
        for (int i = threads.size(); i < consumers.size(); i++) consumers.get(i).close(API_TIMEOUT);
        if (producer != null) producer.close(API_TIMEOUT);
        if (admin != null) admin.close(API_TIMEOUT);
        consumers.clear();
        threads.clear();
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
    @Override public boolean isAutoStartup() { return true; }
}
