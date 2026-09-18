package com.boke.qcmeta.management;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public final class ManagementModels {
    private ManagementModels() { }
    public record DisplayNameInput(String newName) { }
    public record TemplateInput(String wabaId, String name, String language, String category,
                                Integer messageSendTtlSeconds, JsonNode components,
                                String subCategory, Boolean ctaUrlLinkTrackingOptedOut) { }

}
