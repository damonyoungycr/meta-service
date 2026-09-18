package com.boke.qcmeta.model.db;
import java.time.Instant;
public final class MessageCommands {
    private MessageCommands() {}
    public record ProviderResult(String messageId, String state, String providerId, String wamid,
                                 String incoming, Instant at, String errorCode) {}
    public record Failure(String messageId, String code, String error, Instant at) {}
    public record Reply(String businessId, String senderId, String recipientType, String recipient, String localId, String senderPhone) {}
    public record OutboxRetry(long outboxId, String owner, String error, Instant nextTime) {}
    public record EventSnapshot(String eventId, String messageId) {}
}
