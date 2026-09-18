package com.boke.qcmeta.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class ApiModels {

    private ApiModels() {
    }

    public record SubmitMessageRequest(
            String sendRequestId,
            String businessId,
            String senderId,
            String customerId,
            String recipientType,
            String recipient,
            String deliveryMode,
            MessageContent content,
            Instant sendAt,
            Boolean filterUnsubscribed,
            Boolean filterBlocked,
            String sourceId,
            String senderPhone,
            String wabaId
    ) {
    }

    public record SubmitMessageResponse(
            String messageId,
            String state,
            Instant acceptedAt,
            boolean duplicatedRequest
    ) {
    }

    public record MessageView(
            String messageId,
            String sendRequestId,
            String businessId,
            String senderId,
            String customerId,
            String recipientType,
            String recipient,
            String messageKind,
            String deliveryMode,
            String state,
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

    public record ServiceEvent(
            long sequence,
            String eventId,
            String eventType,
            String businessId,
            String senderId,
            String entityId,
            JsonNode payload,
            Instant occurredAt
    ) {
    }

    public record EventsView(List<ServiceEvent> items) {
    }

    public record AckEventsRequest(List<Long> sequences) {
    }

    public record AckEventsResponse(int acknowledged) {
    }

    public record PullEventsRequest(String businessId, String consumerId, Integer limit) { }
    public record EventBatch(String leaseToken, Instant leasedUntil, List<ServiceEvent> items) { }
    public record ConfirmEventsRequest(String businessId, String consumerId, String leaseToken,
                                       List<Long> sequences) { }
    public record ReplayEventsRequest(String businessId, List<Long> sequences) { }
}
