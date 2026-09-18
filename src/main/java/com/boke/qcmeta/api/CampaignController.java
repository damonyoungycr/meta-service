package com.boke.qcmeta.api;

import com.boke.qcmeta.campaign.CampaignApplicationService;
import com.boke.qcmeta.campaign.CampaignModels;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/campaigns")
public class CampaignController {
    private final CampaignApplicationService messages;

    public CampaignController(CampaignApplicationService messages) {
        this.messages = messages;
    }

    @PostMapping
    public CampaignModels.View create(@RequestBody CampaignModels.CreateRequest request) {
        return messages.create(request);
    }

    @GetMapping("/{campaignId}")
    public CampaignModels.View get(@PathVariable String campaignId, @RequestParam String businessId) {
        return messages.get(businessId, campaignId);
    }

    @PostMapping("/{campaignId}/cancel")
    public CampaignModels.View cancel(@PathVariable String campaignId, @RequestParam String businessId) {
        return messages.cancel(businessId, campaignId);
    }
}
