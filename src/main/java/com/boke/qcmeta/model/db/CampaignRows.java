package com.boke.qcmeta.model.db;
import java.time.Instant;
import com.boke.qcmeta.campaign.CampaignModels;
public final class CampaignRows {
    private CampaignRows() {}
    public record Fingerprint(String businessId, String requestFingerprint) {}
    public record Create(String campaignId, String businessId, String senderId, String name,
                         int total, String fingerprint, String sourceId) {}
    public record Statistics(String campaignId, String businessId, String senderId, String name, String state,
                             int total, Instant createdAt, int pending, int submitting, int accepted, int sent,
                             int delivered, int readCount, int failed, int unknownCount, int cancelled) {
        public CampaignModels.View view(boolean duplicate) {
            boolean finished = pending + submitting == 0;
            String status = state.equals("CANCELLED") ? state : unknownCount > 0 ? "NEEDS_ATTENTION" : finished ? "COMPLETED" : "ACTIVE";
            return new CampaignModels.View(campaignId,businessId,senderId,name,status,total,pending,submitting,
                    accepted,sent,delivered,readCount,failed,unknownCount,cancelled,total-pending-submitting,
                    finished,duplicate,createdAt);
        }
    }
}
