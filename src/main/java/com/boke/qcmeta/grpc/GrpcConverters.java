package com.boke.qcmeta.grpc;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.grpc.v1.*;
import com.boke.qcmeta.message.MessageService;
import com.boke.qcmeta.model.ApiModels;
import com.boke.qcmeta.model.MessageContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GrpcConverters {
    private GrpcConverters() {
    }

    static ApiModels.SubmitMessageRequest submitRequest(
            com.boke.qcmeta.grpc.v1.SubmitMessageRequest source, ObjectMapper objectMapper) {
        return new ApiModels.SubmitMessageRequest(
                source.getSendRequestId(), source.getBusinessId(), source.getSenderId(),
                source.getCustomerId(), recipient(source.getRecipientType()), source.getRecipient(),
                delivery(source.getDeliveryMode()), content(source.getContent(), objectMapper),
                instant(source.hasSendAt() ? source.getSendAt() : null),
                source.hasFilterUnsubscribed() ? source.getFilterUnsubscribed() : null,
                source.hasFilterBlocked() ? source.getFilterBlocked() : null,source.getSourceId(),source.getSenderPhone(),source.getWabaId());
    }

    static com.boke.qcmeta.grpc.v1.SubmitMessageResponse submitResponse(
            ApiModels.SubmitMessageResponse source) {
        return com.boke.qcmeta.grpc.v1.SubmitMessageResponse.newBuilder()
                .setMessageId(source.messageId())
                .setState(state(source.state()))
                .setAcceptedAt(timestamp(source.acceptedAt()))
                .setDuplicatedRequest(source.duplicatedRequest())
                .build();
    }

    static com.boke.qcmeta.grpc.v1.Message message(ApiModels.MessageView source) {
        var builder = com.boke.qcmeta.grpc.v1.Message.newBuilder()
                .setMessageId(text(source.messageId())).setSendRequestId(text(source.sendRequestId()))
                .setBusinessId(text(source.businessId())).setSenderId(text(source.senderId()))
                .setCustomerId(text(source.customerId())).setSenderPhone(text(source.senderPhone())).setWabaId(text(source.wabaId())).setRecipientType(recipient(source.recipientType()))
                .setRecipient(text(source.recipient())).setKind(kind(source.messageKind()))
                .setDeliveryMode(delivery(source.deliveryMode())).setState(state(source.state()))
                .setProviderMessageId(text(source.providerMessageId()))
                .setWhatsappMessageId(text(source.whatsappMessageId()))
                .setLastErrorCode(text(source.lastErrorCode()))
                .setLastErrorMessage(text(source.lastErrorMessage()));
        set(builder::setAcceptedAt, source.acceptedAt());
        set(builder::setSubmittedAt, source.submittedAt());
        set(builder::setSentAt, source.sentAt());
        set(builder::setDeliveredAt, source.deliveredAt());
        set(builder::setReadAt, source.readAt());
        set(builder::setFailedAt, source.failedAt());
        return builder.build();
    }

    static ServiceEvent event(ApiModels.ServiceEvent source) {
        return ServiceEvent.newBuilder().setSequence(source.sequence()).setEventId(text(source.eventId()))
                .setEventType(text(source.eventType())).setBusinessId(text(source.businessId()))
                .setSenderId(text(source.senderId())).setEntityId(text(source.entityId()))
                .setPayloadJson(source.payload().toString()).setOccurredAt(timestamp(source.occurredAt())).build();
    }

    private static MessageContent content(com.boke.qcmeta.grpc.v1.MessageContent source,
                                          ObjectMapper objectMapper) {
        String type = kind(source.getKind()).name().replace("MESSAGE_KIND_", "").toLowerCase(Locale.ROOT);
        MessageContent.TextContent text = source.hasText()
                ? new MessageContent.TextContent(source.getText().getBody(), source.getText().getPreviewUrl()) : null;
        MessageContent.TemplateContent template = source.hasTemplate()
                ? new MessageContent.TemplateContent(source.getTemplate().getName(),
                source.getTemplate().getLanguageCode(), components(source.getTemplate().getComponentsList())) : null;
        MessageContent.MediaContent media = source.hasMedia()
                ? new MessageContent.MediaContent(source.getMedia().getId(), source.getMedia().getLink(),
                source.getMedia().getCaption(), source.getMedia().getFilename(), source.getMedia().getAssetId()) : null;
        MessageContent.LocationContent location = source.hasLocation()
                ? location(source.getLocation()) : null;
        MessageContent.ReactionContent reaction = source.hasReaction()
                ? new MessageContent.ReactionContent(source.getReaction().getMessageId(),
                source.getReaction().getEmoji()) : null;
        com.fasterxml.jackson.databind.JsonNode structured = null;
        if (source.hasStructured() && !source.getStructured().getJson().isBlank()) {
            try {
                structured = objectMapper.readTree(source.getStructured().getJson());
            } catch (Exception exception) {
                throw ApiException.badRequest("structured.json 不是有效 JSON");
            }
        }
        return new MessageContent(type, text, media, template, location, reaction,
                structured, source.getReplyToWhatsappMessageId(),source.getReplyToMessageId());
    }

    private static List<MessageContent.TemplateComponent> components(List<TemplateComponent> values) {
        return values.stream().map(component -> new MessageContent.TemplateComponent(
                component.getType(), component.getSubType(),
                component.getIndex() != 0 || component.getType().equalsIgnoreCase("button")
                        ? component.getIndex() : null,
                component.getParametersList().stream().map(parameter ->
                        new MessageContent.TemplateParameter(
                                parameter.getType(), parameter.getText(), parameter.getMediaId(),
                                parameter.getLink(), parameter.getPayload(), parameter.getCouponCode(),
                                parameter.hasLocation() ? location(parameter.getLocation()) : null,
                                parameter.getAssetId(),parameter.getParameterName(),parseJson(parameter.getCurrencyJson()),parseJson(parameter.getDateTimeJson())))
                        .toList())).toList();
    }

    private static MessageContent.LocationContent location(LocationContent source) {
        return new MessageContent.LocationContent(source.getLatitude(), source.getLongitude(),
                source.getName(), source.getAddress());
    }
    private static com.fasterxml.jackson.databind.JsonNode parseJson(String value) {
        if(value==null || value.isBlank()) return null;
        try { return new ObjectMapper().readTree(value); }
        catch(java.io.IOException e) { throw ApiException.badRequest("参数 JSON 格式无效"); }
    }

    static RecipientType recipient(String value) {
        return "BSUID".equalsIgnoreCase(value) ? RecipientType.RECIPIENT_TYPE_BSUID
                : "PHONE".equalsIgnoreCase(value) ? RecipientType.RECIPIENT_TYPE_PHONE
                : RecipientType.RECIPIENT_TYPE_UNSPECIFIED;
    }

    static String recipient(RecipientType value) {
        return value == RecipientType.RECIPIENT_TYPE_BSUID ? "BSUID"
                : value == RecipientType.RECIPIENT_TYPE_PHONE ? "PHONE" : "";
    }

    static DeliveryMode delivery(String value) {
        return "DIRECT".equalsIgnoreCase(value) ? DeliveryMode.DELIVERY_MODE_DIRECT
                : "ASYNC".equalsIgnoreCase(value) ? DeliveryMode.DELIVERY_MODE_ASYNC
                : DeliveryMode.DELIVERY_MODE_UNSPECIFIED;
    }

    static String delivery(DeliveryMode value) {
        return value == DeliveryMode.DELIVERY_MODE_DIRECT ? "DIRECT"
                : value == DeliveryMode.DELIVERY_MODE_ASYNC ? "ASYNC" : "";
    }

    static MessageKind kind(String value) {
        try {
            return MessageKind.valueOf("MESSAGE_KIND_" + text(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return MessageKind.MESSAGE_KIND_UNSPECIFIED;
        }
    }

    static MessageKind kind(MessageKind value) {
        return value == null ? MessageKind.MESSAGE_KIND_UNSPECIFIED : value;
    }

    static MessageState state(String value) {
        try {
            return MessageState.valueOf("MESSAGE_STATE_" + text(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return MessageState.MESSAGE_STATE_UNSPECIFIED;
        }
    }

    static Timestamp timestamp(Instant value) {
        if (value == null) return Timestamp.getDefaultInstance();
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }

    static Instant instant(Timestamp value) {
        return value == null ? null : Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static void set(java.util.function.Consumer<Timestamp> setter, Instant value) {
        if (value != null) setter.accept(timestamp(value));
    }
}
