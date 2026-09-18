package com.boke.qcmeta.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageContent(
        String type,
        TextContent text,
        MediaContent media,
        TemplateContent template,
        LocationContent location,
        ReactionContent reaction,
        JsonNode structured,
        String replyTo,
        String replyToMessageId
) {
    public MessageContent {
        // 提交和旧消息反序列化都在这里归一，避免模板检查与实际发送识别成不同类型。
        type = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }

    public MessageContent(String type, TextContent text, MediaContent media, TemplateContent template,
            LocationContent location, ReactionContent reaction, JsonNode structured, String replyTo) {
        this(type,text,media,template,location,reaction,structured,replyTo,null);
    }
    public record TextContent(String body, Boolean previewUrl) {
    }

    public record MediaContent(String id, String link, String caption, String filename, String assetId) {
        public MediaContent(String id,String link,String caption,String filename) {
            this(id,link,caption,filename,null);
        }
    }

    public record TemplateContent(String name, String language, List<TemplateComponent> components) {
        public TemplateContent {
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    public record TemplateComponent(String type, String subType, Integer index,
                                    List<TemplateParameter> parameters) {
        public TemplateComponent {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    public record TemplateParameter(String type, String text, String mediaId, String link,
                                    String payload, String couponCode, LocationContent location,
                                    String assetId, String parameterName, JsonNode currency, JsonNode dateTime) {
        public TemplateParameter(String type,String text,String mediaId,String link,String payload,
                String couponCode,LocationContent location) {
            this(type,text,mediaId,link,payload,couponCode,location,null,null,null,null);
        }
    }

    public record LocationContent(double latitude, double longitude, String name, String address) {
    }

    public record ReactionContent(String messageId, String emoji) {
    }
}
