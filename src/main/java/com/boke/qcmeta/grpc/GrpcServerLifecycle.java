package com.boke.qcmeta.grpc;

import com.boke.qcmeta.config.QcProperties;
import com.boke.qcmeta.config.YCloudProperties;
import com.boke.qcmeta.media.AssetProperties;
import com.boke.qcmeta.media.MediaRules;
import com.boke.qcmeta.queue.KafkaClient;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import com.boke.qcmeta.mapper.HealthMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跟随 Spring Boot 一起启动和停止 gRPC 服务。
 *
 * <p>gRPC 和 HTTP 共用同一套业务 Service，所以字段校验、幂等规则和数据库事务完全一致。
 * gRPC 健康状态会同时检查 MySQL、YCloud 配置和 Kafka。</p>
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private final QcProperties properties;
    private final AssetProperties assets;
    private final List<io.grpc.BindableService> services;
    private final HealthMapper database;
    private final YCloudProperties config;
    private final com.boke.qcmeta.config.DownstreamProperties downstream;
    private final KafkaClient queue;
    private final HealthStatusManager health = new HealthStatusManager();
    private final AtomicBoolean running = new AtomicBoolean();
    private Server server;

    public GrpcServerLifecycle(QcProperties properties,
                               List<io.grpc.BindableService> services, HealthMapper database,
                               YCloudProperties config, KafkaClient queue, AssetProperties assets, com.boke.qcmeta.config.DownstreamProperties downstream) {
        this.properties = properties;
        this.assets = assets;
        this.services = services;
        this.database = database;
        this.config = config;
        this.downstream = downstream;
        this.queue = queue;
    }

    @Override
    public void start() {
        long mediaLimit = Math.min(assets.getMaxBytes(), MediaRules.maxBytes("application/pdf"));
        // 文件之外预留 1 MiB 给协议字段；较小的素材配置不能降低其他接口原有的 4 MiB 上限。
        int requestLimit = Math.toIntExact(Math.max(4L * 1024 * 1024, mediaLimit + 1024 * 1024));
        NettyServerBuilder builder = NettyServerBuilder.forPort(properties.grpcPort())
                .maxInboundMessageSize(requestLimit);
        services.forEach(builder::addService);
        builder.addService(health.getHealthService());
        try {
            server = builder.build().start();
            running.set(true);
            log.info("gRPC 服务已启动，port={}", properties.grpcPort());
        } catch (IOException exception) {
            throw new IllegalStateException("启动 gRPC 服务失败", exception);
        }
    }

    @Scheduled(fixedDelay = 5000)
    void updateHealth() {
        boolean databaseReady;
        try {
            databaseReady = Integer.valueOf(1).equals(database.ping());
        } catch (RuntimeException exception) {
            databaseReady = false;
        }
        health.setStatus("", databaseReady && config.ready() && downstream.ready() && queue.ready()
                ? ServingStatus.SERVING : ServingStatus.NOT_SERVING);
    }

    @Override
    public void stop() {
        running.set(false);
        health.enterTerminalState();
        if (server == null) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(properties.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                server.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() { return running.get(); }
    @Override
    public boolean isAutoStartup() { return true; }
    @Override
    public int getPhase() { return Integer.MAX_VALUE - 50; }
}
