package com.boke.qcmeta.model.db;
import java.time.Instant;
public final class AssetCommands {
    private AssetCommands() {}
    public record Upload(String assetId, String businessId, String senderId, String filename,
                         String mimeType, long sizeBytes, String objectKey, String url, String senderPhone) {}
public record Provider(String assetId, String businessId, String mediaId, Instant expiresAt) {}
    public record Downloaded(String assetId, String claimId, String objectKey, String url, long sizeBytes) {}
}
