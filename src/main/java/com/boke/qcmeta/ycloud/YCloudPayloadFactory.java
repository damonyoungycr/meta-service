package com.boke.qcmeta.ycloud;

import com.boke.qcmeta.model.MessageContent;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class YCloudPayloadFactory {

    private final ObjectMapper objectMapper;

    public YCloudPayloadFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode create(MessageRecord message) {
        MessageContent content = content(message);
        String type = content.type().trim().toLowerCase(Locale.ROOT);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("from", message.senderPhone());
        request.put("type", type);
        request.put("externalId", message.messageId());
        if (message.recipientType().equals("PHONE")) {
            request.put("to", message.recipient());
        } else {
            request.put("recipient", message.recipient());
        }
        if (content.replyTo() != null && !content.replyTo().isBlank()) {
            request.putObject("context").put("message_id", content.replyTo());
        }
        if (message.deliveryMode().equals("ASYNC")) {
            request.put("filterUnsubscribed", message.filterUnsubscribed());
            request.put("filterBlocked", message.filterBlocked());
        }

        switch (type) {
            case "text" -> {
                ObjectNode text = request.putObject("text");
                text.put("body", content.text().body());
                if (Boolean.TRUE.equals(content.text().previewUrl())) {
                    text.put("preview_url", true);
                }
            }
            case "template" -> request.set("template", template(content.template()));
            case "image", "video", "audio", "document", "sticker" ->
                    request.set(type, media(content.media()));
            case "location" -> request.set("location", location(content.location()));
            case "reaction" -> request.putObject("reaction")
                    .put("message_id", content.reaction().messageId())
                    .put("emoji", content.reaction().emoji());
            case "interactive" -> request.set("interactive", content.structured());
            case "contacts" -> request.set("contacts", content.structured());
            default -> throw new IllegalArgumentException("不支持的消息类型: " + type);
        }
        return request;
    }

    public MessageContent content(MessageRecord message) {
        return objectMapper.convertValue(message.content(), MessageContent.class);
    }

    private ObjectNode template(MessageContent.TemplateContent source) {
        ObjectNode template = objectMapper.createObjectNode();
        template.put("name", source.name());
        template.putObject("language").put("code", source.language());
        if (!source.components().isEmpty()) {
            ArrayNode components = template.putArray("components");
            source.components().forEach(sourceComponent -> {
                ObjectNode component = components.addObject();
                component.put("type", lower(sourceComponent.type()));
                if (sourceComponent.subType() != null && !sourceComponent.subType().isBlank()) {
                    component.put("sub_type", lower(sourceComponent.subType()));
                }
                if (sourceComponent.index() != null) {
                    component.put("index", sourceComponent.index());
                }
                if (!sourceComponent.parameters().isEmpty()) {
                    ArrayNode parameters = component.putArray("parameters");
                    sourceComponent.parameters().forEach(sourceParameter -> {
                        String type = lower(sourceParameter.type());
                        ObjectNode parameter = parameters.addObject().put("type", type);
                        putIfPresent(parameter,"parameter_name",sourceParameter.parameterName());
                        switch (type) {
                            case "text" -> parameter.put("text", sourceParameter.text());
                            case "image", "video", "document", "gif" -> parameter.set(type,
                                    media(new MessageContent.MediaContent(sourceParameter.mediaId(),
                                            sourceParameter.link(), null, null)));
                            case "payload" -> parameter.put("payload", sourceParameter.payload());
                            case "coupon_code" -> parameter.put("coupon_code", sourceParameter.couponCode());
                            case "location" -> parameter.set("location", location(sourceParameter.location()));
                            case "currency" -> parameter.set("currency",sourceParameter.currency());
                            case "date_time" -> parameter.set("date_time",sourceParameter.dateTime());
                            default -> throw new IllegalArgumentException("不支持的模板参数: " + type);
                        }
                    });
                }
            });
        }
        return template;
    }

    private ObjectNode media(MessageContent.MediaContent source) {
        ObjectNode media = objectMapper.createObjectNode();
        putIfPresent(media, "id", source.id());
        putIfPresent(media, "link", source.link());
        putIfPresent(media, "caption", source.caption());
        putIfPresent(media, "filename", source.filename());
        return media;
    }

    private ObjectNode location(MessageContent.LocationContent source) {
        ObjectNode location = objectMapper.createObjectNode();
        location.put("latitude", source.latitude());
        location.put("longitude", source.longitude());
        putIfPresent(location, "name", source.name());
        putIfPresent(location, "address", source.address());
        return location;
    }

    private void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
