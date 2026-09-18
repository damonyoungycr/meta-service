package com.boke.qcmeta.message;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** 单 JVM 按号码排队，等待期间不修改消息、不重复发布 Kafka 任务。 */
@Component
public class SenderRateLimiter {
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    public void await(String senderId, int rate) throws InterruptedException {
        Slot slot = slots.computeIfAbsent(senderId, ignored -> new Slot());
        slot.lock.lockInterruptibly();
        try {
            while (true) {
                long interval = TimeUnit.SECONDS.toNanos(1) / Math.max(1, rate);
                long remaining = interval - (System.nanoTime() - slot.lastStart);
                if (remaining <= 0) {
                    slot.lastStart = System.nanoTime();
                    return;
                }
                TimeUnit.NANOSECONDS.sleep(remaining);
            }
        } finally {
            slot.lock.unlock();
        }
    }

    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock(true);
        // 首次发送也等一个间隔，避免重启后紧接着上一个进程的发送。
        long lastStart = System.nanoTime();
    }
}
