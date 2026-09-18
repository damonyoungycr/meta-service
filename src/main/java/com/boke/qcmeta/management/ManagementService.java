package com.boke.qcmeta.management;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.management.ManagementModels.TemplateInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.boke.qcmeta.management.YCloudManagementClient.segment;

@Service
public class ManagementService {
    private final YCloudManagementClient client;
    private final ObjectMapper mapper;

    public ManagementService(YCloudManagementClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }

    public JsonNode listWabas(int page, int limit) {
        JsonNode result = client.get("/businessAccounts", page(page, limit));
        items(result);
        return result;
    }

    public JsonNode getWaba(String waba) {
        required(waba, "wabaId", 128);
        JsonNode result = client.get("/businessAccounts/" + segment(waba), Map.of());
        if (!waba.equals(result.path("id").asText())) throw invalidResponse();
        return result;
    }

    public JsonNode listPhones(String waba, int page, int limit) {
        required(waba, "wabaId", 128);
        Map<String, String> params = new HashMap<>(page(page, limit)); params.put("filter.wabaId", waba);
        JsonNode result = client.get("/phoneNumbers", params);
        items(result);
        return result;
    }

    public JsonNode getPhone(String waba, String phone) {
        JsonNode result = client.get(phonePath(waba, phone), Map.of());
        requireWaba(result, waba);
        if (!phone.equals(result.path("phoneNumber").asText())) throw invalidResponse();
        return result;
    }

    public JsonNode registerPhone(String waba, String phoneNumberId) {
        required(waba, "wabaId", 128);
        if (phoneNumberId == null || !phoneNumberId.matches("[0-9]{1,32}")) {
            throw ApiException.badRequest("phoneNumberId 必须是平台号码 ID，不能传 E.164 手机号");
        }
        JsonNode result = client.request("POST", "/phoneNumbers/" + segment(waba) + "/"
                + segment(phoneNumberId) + "/register", null);
        requireWaba(result, waba);
        if (!phoneNumberId.equals(result.path("id").asText())) throw invalidResponse();
        String phone = result.path("phoneNumber").asText(); validatePhone(phone);
        return result;
    }

    public JsonNode getProfile(String waba, String phone) {
        JsonNode result = client.get(phonePath(waba, phone) + "/profile", Map.of());
        return result;
    }

    public JsonNode updateProfile(String waba, String phone, JsonNode input) {
        validateProfile(input);
        JsonNode result = client.request("PATCH", phonePath(waba, phone) + "/profile", input);
        return result;
    }

    public JsonNode updateDisplayName(String waba, String phone, String newName) {
        required(newName, "newName", 150);
        if (newName.length() < 3) throw ApiException.badRequest("newName 长度应为 3 到 150 字符");
        JsonNode result = client.request("PATCH", phonePath(waba, phone) + "/displayName",
                mapper.createObjectNode().put("newName", newName));
        return result;
    }

    public JsonNode listTemplates(String waba, int page, int limit, String name, String language, String status) {
        required(waba, "wabaId", 128);
        Map<String, String> params = new HashMap<>(page(page, limit)); params.put("filter.wabaId", waba);
        if (name != null && !name.isBlank()) params.put("filter.name", required(name, "name", 512));
        if (language != null && !language.isBlank()) params.put("filter.language", required(language, "language", 32));
        if (status != null && !status.isBlank()) {
            Set<String> statuses = Set.of("PENDING", "REJECTED", "APPROVED", "PAUSED", "DISABLED", "ARCHIVED", "IN_APPEAL", "DELETED");
            for (String part : status.split(",", -1)) if (!statuses.contains(part)) throw ApiException.badRequest("模板 status 无效");
            params.put("filter.status", status);
        }
        JsonNode result = client.get("/templates", params);
        return result;
    }

    public JsonNode getTemplate(String waba, String name, String language) {
        JsonNode result = client.get(templatePath(waba, name, language), Map.of());
        requireTemplateKey(result, waba, name, language);
        return result;
    }

    public JsonNode usableTemplate(String waba, String name, String language) {
        JsonNode result = getTemplate(waba, name, language);
        if (!"APPROVED".equals(result.path("status").asText())) {
            throw ApiException.conflict("模板尚未通过审核、已暂停或不可用");
        }
        return result;
    }

    public JsonNode createTemplate(TemplateInput input) {
        if (input == null) throw ApiException.badRequest("模板内容不能为空");
        templatePath(input.wabaId(), input.name(), input.language());
        validateTemplate(input, input.category());
        ObjectNode body = templateBody(input).put("wabaId", input.wabaId()).put("name", input.name())
                .put("language", input.language()).put("category", input.category());
        if (input.subCategory() != null && !input.subCategory().isBlank()) {
            if (!"ORDER_STATUS".equals(input.subCategory()) || !"UTILITY".equals(input.category()))
                throw ApiException.badRequest("subCategory=ORDER_STATUS 仅适用于 UTILITY");
            body.put("subCategory", input.subCategory());
        }
        JsonNode result = client.request("POST", "/templates", body);
        requireTemplateKey(result, input.wabaId(), input.name(), input.language());
        return result;
    }

    public JsonNode updateTemplate(String waba, String name, String language, TemplateInput input) {
        if (input == null) throw ApiException.badRequest("模板内容不能为空");
        JsonNode current = getTemplate(waba, name, language);
        if (!Set.of("APPROVED", "REJECTED", "PAUSED").contains(current.path("status").asText()))
            throw ApiException.conflict("只有 APPROVED、REJECTED、PAUSED 模板允许修改");
        String category = current.path("category").asText();
        if (input.category() != null && !input.category().isBlank() && !input.category().equals(category))
            throw ApiException.badRequest("此接口不能修改模板类别，请新建另一份模板");
        if (input.subCategory() != null && !input.subCategory().isBlank()
                && !input.subCategory().equals(current.path("subCategory").asText()))
            throw ApiException.badRequest("此接口不能修改模板子类别");
        validateTemplate(input, category);
        JsonNode result = client.request("PATCH", templatePath(waba, name, language), templateBody(input));
        requireTemplateKey(result, waba, name, language);
        return result;
    }

    public JsonNode deleteTemplate(String waba, String name, String language) {
        return client.request("DELETE", templatePath(waba, name, language), null);
    }

    private ObjectNode templateBody(TemplateInput input) {
        ObjectNode result = mapper.createObjectNode(); result.set("components", input.components().deepCopy());
        if (input.messageSendTtlSeconds() != null) result.put("messageSendTtlSeconds", input.messageSendTtlSeconds());
        if (input.ctaUrlLinkTrackingOptedOut() != null) result.put("ctaUrlLinkTrackingOptedOut", input.ctaUrlLinkTrackingOptedOut());
        return result;
    }

    static void validateTemplate(TemplateInput input, String category) {
        if (!Set.of("AUTHENTICATION", "UTILITY", "MARKETING").contains(category == null ? "" : category))
            throw ApiException.badRequest("category 必须为 AUTHENTICATION、UTILITY 或 MARKETING");
        Integer ttl = input.messageSendTtlSeconds();
        if (ttl != null && ttl != -1) {
            int min = category.equals("MARKETING") ? 43200 : 30;
            int max = switch (category) { case "AUTHENTICATION" -> 900; case "UTILITY" -> 43200; default -> 2592000; };
            if (ttl < min || ttl > max) throw ApiException.badRequest("messageSendTtlSeconds 不符合模板类别范围");
        }
        JsonNode components = input.components();
        if (components == null || !components.isArray() || components.isEmpty() || components.size() > 7)
            throw ApiException.badRequest("components 必须完整提供模板组件数组");
        if (components.toString().length() > 256 * 1024) throw ApiException.badRequest("模板组件内容过大");
        Set<String> types = new HashSet<>();
        for (JsonNode component : components) {
            String type = component.path("type").asText();
            if (!Set.of("BODY", "HEADER", "FOOTER", "BUTTONS", "LIMITED_TIME_OFFER", "CAROUSEL", "CALL_PERMISSION_REQUEST").contains(type)
                    || !types.add(type)) throw ApiException.badRequest("模板组件类型无效或重复");
            if (type.equals("HEADER") && !Set.of("TEXT", "IMAGE", "GIF", "VIDEO", "DOCUMENT", "LOCATION").contains(component.path("format").asText()))
                throw ApiException.badRequest("HEADER 必须指定有效的 format");
            if (component.path("example").has("header_handle"))
                throw ApiException.badRequest("YCloud 模板示例使用 example.header_url，不能使用 Meta header_handle");
            if (type.equals("HEADER") && Set.of("IMAGE", "GIF", "VIDEO", "DOCUMENT").contains(component.path("format").asText())) {
                JsonNode urls = component.path("example").path("header_url");
                if (!urls.isArray() || urls.isEmpty()) throw ApiException.badRequest("媒体 HEADER 必须提供 example.header_url 示例素材地址");
                for (JsonNode url : urls) {
                    if (!url.isTextual()) throw ApiException.badRequest("example.header_url 必须包含有效地址");
                    validateUrl(url.asText(), "example.header_url");
                }
            }
            if (type.equals("BODY") && !category.equals("AUTHENTICATION") && component.path("text").asText().isBlank())
                throw ApiException.badRequest("BODY.text 不能为空");
            int max = type.equals("BODY") ? 1024 : 60;
            if (Set.of("BODY", "HEADER", "FOOTER").contains(type) && component.path("text").asText().length() > max)
                throw ApiException.badRequest(type + ".text 超过长度限制");
            if (type.equals("BUTTONS") && (!component.path("buttons").isArray() || component.path("buttons").isEmpty() || component.path("buttons").size() > 10))
                throw ApiException.badRequest("BUTTONS.buttons 必须包含 1 到 10 个按钮");
            if (type.equals("CAROUSEL") && (!component.path("cards").isArray() || component.path("cards").isEmpty() || component.path("cards").size() > 10))
                throw ApiException.badRequest("CAROUSEL.cards 必须包含 1 到 10 张卡片");
            if (component.has("code_expiration_minutes") && (!category.equals("AUTHENTICATION") || !type.equals("FOOTER")
                    || !component.path("code_expiration_minutes").isIntegralNumber()
                    || component.path("code_expiration_minutes").asInt() < 1 || component.path("code_expiration_minutes").asInt() > 90))
                throw ApiException.badRequest("验证码有效期仅用于 AUTHENTICATION 的 FOOTER，范围 1 到 90 分钟");
        }
        if (!types.contains("BODY")) throw ApiException.badRequest("模板必须包含 BODY");
    }

    static void validateProfile(JsonNode input) {
        Set<String> allowed = Set.of("about", "address", "description", "email", "profilePictureUrl", "vertical", "websites");
        if (input == null || !input.isObject() || input.isEmpty()) throw ApiException.badRequest("资料修改不能为空");
        input.fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw ApiException.badRequest("不支持的资料字段: " + key); });
        Map<String, Integer> limits = Map.of("about", 139, "address", 256, "description", 512, "email", 128);
        limits.forEach((key, limit) -> {
            if (input.has(key) && (!input.get(key).isTextual() || input.get(key).asText().length() > limit
                    || (key.equals("about") && input.get(key).asText().isBlank()))) throw ApiException.badRequest(key + " 格式或长度无效");
        });
        if (input.has("profilePictureUrl")) validateUrl(input.get("profilePictureUrl").asText(), "profilePictureUrl");
        if (input.has("websites")) {
            JsonNode sites = input.get("websites");
            if (!sites.isArray() || sites.size() > 2) throw ApiException.badRequest("websites 最多 2 项");
            for (JsonNode site : sites) { if (!site.isTextual() || site.asText().length() > 255) throw ApiException.badRequest("网站格式或长度无效"); validateUrl(site.asText(), "websites"); }
        }
        if (input.has("vertical") && (!input.get("vertical").isTextual() || !Set.of("", "OTHER", "AUTO", "BEAUTY", "APPAREL", "EDU", "ENTERTAIN", "EVENT_PLAN", "FINANCE", "GROCERY", "GOVT", "HOTEL", "HEALTH", "NONPROFIT", "PROF_SERVICES", "RETAIL", "TRAVEL", "RESTAURANT").contains(input.get("vertical").asText())))
            throw ApiException.badRequest("vertical 无效");
    }

    private static void validateUrl(String value, String field) {
        try { URI uri = URI.create(value); if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw ApiException.badRequest(field + " 必须是有效的 HTTP/HTTPS 地址"); }
    }

    private static Map<String, String> page(int page, int limit) {
        if (page < 1 || page > 100 || limit < 1 || limit > 100) throw ApiException.badRequest("page、limit 范围均为 1 到 100");
        return Map.of("page", Integer.toString(page), "limit", Integer.toString(limit), "includeTotal", "true");
    }
    private static String phonePath(String waba, String phone) { required(waba, "wabaId", 128); validatePhone(phone); return "/phoneNumbers/" + segment(waba) + "/" + segment(phone); }
    private static void validatePhone(String phone) { if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}")) throw ApiException.badRequest("phoneNumber 必须是带 + 的 E.164 手机号"); }
    private static String templatePath(String waba, String name, String language) {
        required(waba, "wabaId", 128); required(name, "name", 512); required(language, "language", 32);
        if (!name.matches("[a-z0-9_]{1,512}") || !language.matches("[A-Za-z_]{2,32}")) throw ApiException.badRequest("模板 name 或 language 格式无效");
        return "/templates/" + segment(waba) + "/" + segment(name) + "/" + segment(language);
    }
    private static String required(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) throw ApiException.badRequest(name + " 不能为空，且必须符合长度和格式限制");
        return value;
    }
    private static JsonNode items(JsonNode result) { if (!result.path("items").isArray()) throw invalidResponse(); return result.path("items"); }
    private static void requireWaba(JsonNode result, String waba) { if (!waba.equals(result.path("wabaId").asText())) throw invalidResponse(); }
    private static void requireTemplateKey(JsonNode result, String waba, String name, String language) { requireWaba(result, waba); if (!name.equals(result.path("name").asText()) || !language.equals(result.path("language").asText())) throw invalidResponse(); }
    private static ApiException invalidResponse() { return new ApiException(HttpStatus.BAD_GATEWAY, "YCloud 响应缺少有效资源标识，无法确认管理结果，请刷新核对"); }

}
