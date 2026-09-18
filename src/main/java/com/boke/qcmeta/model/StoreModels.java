package com.boke.qcmeta.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public final class StoreModels {

    private StoreModels() {
    }

    public record MessageRecord(
            String messageId,
            String sendRequestId,
            String businessId,
            String senderId,
            String customerId,
            String recipientType,
            String recipient,
            String messageKind,
            String deliveryMode,
            JsonNode content,
            String campaignId,
            String campaignRecipientId,
            String state,
            boolean filterUnsubscribed,
            boolean filterBlocked,
            Instant sendAt,
            Instant nextAttemptAt,
            int attemptCount,
            String providerMessageId,
            String whatsappMessageId,
            String lastErrorCode,
            String lastErrorMessage,
            Instant acceptedAt,
            Instant submittedAt,
            Instant sentAt,
            Instant deliveredAt,
            Instant readAt,
            Instant failedAt,
            String senderPhone,
            String wabaId
    ) {
    }

    public record NewMessage(
            String messageId,
            String sendRequestId,
            String businessId,
            String senderId,
            String customerId,
            String recipientType,
            String recipient,
            String messageKind,
            String deliveryMode,
            JsonNode content,
            String campaignId,
            String campaignRecipientId,
            boolean filterUnsubscribed,
            boolean filterBlocked,
            Instant sendAt,
            String requestFingerprint,
            String sourceId,
            String senderPhone,
            String wabaId
    ) {
    }

    public record OutboxRecord(long outboxId, String messageId, int publishAttempts) {
    }

    public record AttemptRecord(
            String attemptId,
            String messageId,
            Instant startedAt,
            Instant finishedAt,
            String result,
            Integer httpStatus,
            String providerRequestId,
            String errorCode,
            String errorMessage
    ) {
    }

    public record WebhookEvent(
            String eventId,
            String eventType,
            JsonNode payload,
            Instant occurredAt,
            String providerMessageId,
            String whatsappMessageId,
            String externalId,
            String status,
            String errorCode,
            String errorMessage,
            String senderPhone
    ) {
    }
}
