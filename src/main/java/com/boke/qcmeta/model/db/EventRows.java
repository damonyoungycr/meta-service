package com.boke.qcmeta.model.db;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
public final class EventRows {
    private EventRows() {}
    public record Pending(JsonNode payload, int processAttempts) {}
    public record Callback(String eventId, JsonNode payload, int attempts) {}
public record Lease(List<Long> sequences, String token, String consumerId, Instant until) {}
    public record Receipt(List<Long> sequences, String businessId, String consumerId, String token) {}
}
