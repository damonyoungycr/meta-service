package com.boke.qcmeta.api;

import com.boke.qcmeta.config.YCloudProperties;
import com.boke.qcmeta.queue.KafkaClient;
import org.springframework.http.ResponseEntity;
import com.boke.qcmeta.mapper.HealthMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final HealthMapper database;
    private final KafkaClient queue;
    private final YCloudProperties config;
    private final com.boke.qcmeta.config.DownstreamProperties downstream;

    public HealthController(HealthMapper database, KafkaClient queue, YCloudProperties config, com.boke.qcmeta.config.DownstreamProperties downstream) {
        this.database = database;
        this.queue = queue;
        this.config = config;
        this.downstream = downstream;
    }

    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        try {
            database.ping();
        } catch (RuntimeException exception) {
            return down("数据库连接不可用");
        }
        if (!queue.ready()) {
            return down("Kafka 连接不可用");
        }
        if (!config.ready()) {
            return down("YCloud API Key 或 Webhook Secret 尚未配置");
        }
        if (!downstream.ready()) return down("downstream.callback-url 尚未配置");
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    private ResponseEntity<Map<String, Object>> down(String reason) {
        return ResponseEntity.status(503).body(Map.of("status", "DOWN", "reason", reason));
    }
}
