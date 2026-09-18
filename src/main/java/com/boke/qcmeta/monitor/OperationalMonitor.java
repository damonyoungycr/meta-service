package com.boke.qcmeta.monitor;

import com.boke.qcmeta.config.EventProperties;
import com.boke.qcmeta.store.EventRepository;
import com.boke.qcmeta.store.MessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OperationalMonitor {

    private static final Logger log = LoggerFactory.getLogger(OperationalMonitor.class);
    private final MessageRepository messages;
    private final EventRepository events;
    private final EventProperties config;
    private final AtomicLong mqPending = new AtomicLong();
    private final AtomicLong eventsPending = new AtomicLong();

    public OperationalMonitor(MessageRepository messages, EventRepository events,
                              EventProperties config, MeterRegistry meters) {
        this.messages = messages;
        this.events = events;
        this.config = config;
        meters.gauge("qc_meta_mq_outbox_pending", mqPending);
        meters.gauge("qc_meta_event_outbox_pending", eventsPending);
    }

    @Scheduled(fixedDelay = 5000)
    void updateGauges() {
        try {
            mqPending.set(messages.countPendingOutbox());
            eventsPending.set(events.countPending());
        } catch (RuntimeException exception) {
            log.warn("刷新积压指标失败", exception);
        }
    }

    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    void cleanupEvents() {
        int days = config.retentionDays();
        int count = events.cleanup(Instant.now().minus(days, ChronoUnit.DAYS));
        if (count > 0) {
            log.info("已清理过期事件，count={}", count);
        }
    }
}
