package com.boke.qcmeta.campaign;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.message.MessageContentValidator;
import com.boke.qcmeta.model.MessageContent;
import com.boke.qcmeta.model.StoreModels.NewMessage;
import com.boke.qcmeta.media.AssetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class CampaignApplicationService {
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private final CampaignRepository campaigns;
    private final MessageContentValidator validator;
    private final ObjectMapper mapper;
    private final AssetService assets;
    public CampaignApplicationService(CampaignRepository campaigns,
            MessageContentValidator validator, ObjectMapper mapper, AssetService assets) {
        this.campaigns = campaigns;
        this.validator = validator; this.mapper = mapper; this.assets = assets;
    }
    public CampaignModels.View create(CampaignModels.CreateRequest request) {
        require(request != null, "请求不能为空");
        id(request.businessId(), 128, "businessId"); id(request.campaignId(), 128, "campaignId");
        var fingerprintInput = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(request);
        // 旧请求没有这些字段，重试时沿用旧指纹；新请求的号码信息参与防重比较。
        if (text(request.senderPhone()).isEmpty()) fingerprintInput.remove("senderPhone");
        if (text(request.wabaId()).isEmpty()) fingerprintInput.remove("wabaId");
        String fingerprint = RequestFingerprint.of(fingerprintInput, mapper);
        String existing = campaigns.fingerprint(request.businessId().trim(), request.campaignId().trim());
        if (existing != null) {
            if (!existing.equals(fingerprint)) throw ApiException.conflict("campaignId 已被不同请求使用");
            return campaigns.get(request.businessId().trim(), request.campaignId().trim(), true);
        }
        id(request.senderId(), 128, "senderId");
        require(text(request.name()).length() <= 255 && text(request.sourceId()).length() <= 128, "name 或 sourceId 太长");
        require(PHONE.matcher(text(request.senderPhone())).matches(), "senderPhone 必须为带 + 的 E.164 发送号码");
        require(text(request.wabaId()).length()<=128, "wabaId 最长 128 字符");
        require(request.recipients() != null && !request.recipients().isEmpty() && request.recipients().size() <= 10000,
                "活动需要 1 至 10000 条收件人记录");
        Instant at = request.sendAt() == null ? Instant.now() : request.sendAt();
        require(!at.isBefore(Instant.now().minusSeconds(60)), "sendAt 不能是已过去的时间");
        boolean unsubscribed = Boolean.TRUE.equals(request.filterUnsubscribed());
        boolean blocked = Boolean.TRUE.equals(request.filterBlocked());
        Set<String> rowIds = new HashSet<>();
        List<NewMessage> messages = new ArrayList<>();
        var resolveAssets = assets.contentResolver(request.businessId().trim(), request.senderId().trim(), request.senderPhone().trim());
        for (var row : request.recipients()) {
            require(row != null, "收件人记录不能为空"); id(row.recipientId(), 128, "recipientId");
            require(rowIds.add(row.recipientId().trim()), "recipientId 不能重复");
            require(text(row.customerId()).length() <= 128, "customerId 太长");
            String type = text(row.recipientType()).toUpperCase(Locale.ROOT);
            require(type.equals("PHONE") ? PHONE.matcher(text(row.recipient())).matches()
                    : type.equals("BSUID") && !text(row.recipient()).isEmpty() && text(row.recipient()).length() <= 255,
                    "recipientType 或 recipient 无效");
            String name = fallback(row.templateName(), request.templateName());
            String language = fallback(row.languageCode(), request.languageCode());
            MessageContent content = new MessageContent("template", null, null,
                    new MessageContent.TemplateContent(name, language, row.components()), null, null, null, null);
            content = mapper.convertValue(resolveAssets.apply(mapper.valueToTree(content)),MessageContent.class);
            validator.validate(content);
            // 固定长度内部请求键避免 campaignId 与 recipientId 拼接碰撞或超长。
            String requestId = "campaign:" + RequestFingerprint.of(List.of(request.campaignId().trim(), row.recipientId().trim()), mapper);
            messages.add(new NewMessage(UUID.randomUUID().toString(), requestId, request.businessId().trim(),
                    request.senderId().trim(), text(row.customerId()), type, text(row.recipient()), "TEMPLATE", "ASYNC",
                    mapper.valueToTree(content), request.campaignId().trim(), row.recipientId().trim(), unsubscribed, blocked, at,
                    RequestFingerprint.of(List.of(fingerprint,row),mapper), text(request.sourceId()), text(request.senderPhone()), text(request.wabaId())));
        }
        return campaigns.create(request, fingerprint, messages);
    }
    public CampaignModels.View get(String businessId, String campaignId) {
        id(businessId,128,"businessId"); id(campaignId,128,"campaignId");
        return campaigns.get(businessId.trim(), campaignId.trim(), false);
    }
    public CampaignModels.View cancel(String businessId, String campaignId) {
        id(businessId,128,"businessId"); id(campaignId,128,"campaignId");
        return campaigns.cancel(businessId.trim(), campaignId.trim());
    }
    private static String fallback(String row, String global) { return text(row).isEmpty() ? text(global) : text(row); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static void id(String value, int max, String name) { require(!text(value).isEmpty() && text(value).length() <= max, name + " 不能为空或超长"); }
    private static void require(boolean test, String message) { if (!test) throw ApiException.badRequest(message); }
}
