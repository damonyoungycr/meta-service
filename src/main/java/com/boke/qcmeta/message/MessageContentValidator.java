package com.boke.qcmeta.message;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.model.MessageContent;
import com.boke.qcmeta.model.MessageContent.LocationContent;
import com.boke.qcmeta.model.MessageContent.TemplateParameter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class MessageContentValidator {

    private static final Set<String> MEDIA_TYPES = Set.of(
            "image", "video", "audio", "document", "sticker");

    public void validate(MessageContent content) {
        if (content == null || blank(content.type())) {
            throw ApiException.badRequest("content.type 不能为空");
        }
        String type = content.type().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "text" -> validateText(content);
            case "template" -> validateTemplate(content);
            case "location" -> validateLocation(content.location(), "位置消息");
            case "reaction" -> {
                if (content.reaction() == null || blank(content.reaction().messageId())) {
                    throw ApiException.badRequest("表情回应必须填写原 WhatsApp 消息 ID");
                }
            }
            case "interactive", "contacts" -> {
                if (content.structured() == null || content.structured().isNull()) {
                    throw ApiException.badRequest(type + " 内容必须是有效 JSON");
                }
            }
            default -> {
                if (MEDIA_TYPES.contains(type)) {
                    validateMedia(content.media(), "媒体消息");
                } else {
                    throw ApiException.badRequest("暂不支持的消息类型: " + content.type());
                }
            }
        }
    }

    private void validateText(MessageContent content) {
        if (content.text() == null || blank(content.text().body())) {
            throw ApiException.badRequest("文本消息的 body 不能为空");
        }
        if (content.text().body().codePointCount(0, content.text().body().length()) > 4096) {
            throw ApiException.badRequest("文本消息不能超过 4096 个字符");
        }
    }

    private void validateTemplate(MessageContent content) {
        if (content.template() == null) {
            throw ApiException.badRequest("模板消息的 template 不能为空");
        }
        if (blank(content.template().name()) || blank(content.template().language())) {
            throw ApiException.badRequest("模板名称和语言不能为空");
        }
        content.template().components().forEach(component -> {
            require(component!=null && !blank(component.type()),"模板组件不能为空且必须填写 type");
            component.parameters().forEach(this::validateParameter);
        });
    }

    private void validateParameter(TemplateParameter parameter) {
        if (parameter == null || blank(parameter.type())) {
            throw ApiException.badRequest("模板参数类型不能为空");
        }
        String type = parameter.type().trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "text" -> require(!blank(parameter.text()), "模板 text 参数不能为空");
            case "image", "video", "document", "gif" ->
                    validateMedia(new MessageContent.MediaContent(
                            parameter.mediaId(), parameter.link(), null, null), "模板 " + type + " 参数");
            case "payload" -> require(!blank(parameter.payload()), "模板 payload 参数不能为空");
            case "coupon_code" -> {
                int length = blank(parameter.couponCode()) ? 0
                        : parameter.couponCode().codePointCount(0, parameter.couponCode().length());
                require(length >= 1 && length <= 15, "模板 coupon_code 必须是 1 到 15 个字符");
            }
            case "location" -> validateLocation(parameter.location(), "模板 location 参数");
            case "currency" -> require(parameter.currency()!=null && parameter.currency().isObject()
                    && parameter.currency().path("code").asText("").matches("[A-Z]{3}")
                    && parameter.currency().path("amount_1000").isIntegralNumber()
                    && !parameter.currency().path("fallback_value").asText("").isBlank(),"currency 必须包含 code、amount_1000、fallback_value");
            case "date_time" -> require(parameter.dateTime()!=null && parameter.dateTime().isObject()
                    && !parameter.dateTime().path("fallback_value").asText("").isBlank(),"date_time 必须包含 fallback_value");
            default -> throw ApiException.badRequest("暂不支持的模板参数类型: " + parameter.type());
        }
    }

    private void validateMedia(MessageContent.MediaContent media, String label) {
        if (media == null) {
            throw ApiException.badRequest(label + "缺少媒体内容");
        }
        boolean hasId = !blank(media.id());
        boolean hasLink = !blank(media.link());
        require(hasId != hasLink, label + "必须且只能填写 id 或 link");
    }

    private void validateLocation(LocationContent location, String label) {
        if (location == null) {
            throw ApiException.badRequest(label + "缺少 location");
        }
        require(location.latitude() >= -90 && location.latitude() <= 90
                        && location.longitude() >= -180 && location.longitude() <= 180,
                label + "的经纬度超出有效范围");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw ApiException.badRequest(message);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
