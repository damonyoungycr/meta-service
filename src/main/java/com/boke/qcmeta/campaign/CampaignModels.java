package com.boke.qcmeta.campaign;

import com.boke.qcmeta.model.MessageContent;
import java.time.Instant;
import java.util.List;

public final class CampaignModels {
    private CampaignModels() { }
    public record Recipient(String recipientId, String customerId, String recipientType, String recipient,
                            List<MessageContent.TemplateComponent> components,
                            String templateName, String languageCode) { }
    public record CreateRequest(String campaignId, String businessId, String senderId, String name,
                                String templateName, String languageCode, List<Recipient> recipients,
                                Instant sendAt, Boolean filterUnsubscribed, Boolean filterBlocked,
                                String sourceId, String senderPhone, String wabaId) { }
    public record View(String campaignId, String businessId, String senderId, String name,
                       String state, int total, int pending, int submitting, int accepted,
                       int sent, int delivered, int read, int failed, int unknown, int cancelled,
                       int processed, boolean taskFinished, boolean duplicatedRequest, Instant createdAt) { }
}
