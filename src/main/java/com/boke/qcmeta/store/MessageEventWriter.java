package com.boke.qcmeta.store;

import com.boke.qcmeta.mapper.MessageEventMapper;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class MessageEventWriter {
    private final MessageEventMapper database;
    public MessageEventWriter(MessageEventMapper database) { this.database = database; }
    // 调用方的状态更新事务同时写事件，避免页面永远等不到本地失败或 UNKNOWN。
    public void write(String messageId) {
        database.insertSnapshot(UUID.randomUUID().toString(), messageId);
    }
    public void write(java.util.List<String> messageIds) {
        for (int start = 0; start < messageIds.size(); start += 100) {
            database.insertSnapshots(messageIds.subList(start, Math.min(start + 100, messageIds.size())).stream()
                    .map(id -> new com.boke.qcmeta.model.db.MessageCommands.EventSnapshot(UUID.randomUUID().toString(), id)).toList());
        }
    }
}
