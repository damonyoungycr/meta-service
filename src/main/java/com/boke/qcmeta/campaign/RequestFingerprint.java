package com.boke.qcmeta.campaign;

import com.boke.qcmeta.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;

public final class RequestFingerprint {
    private RequestFingerprint() { }
    public static String of(Object value, ObjectMapper mapper) {
        try {
            String canonical = canonical(mapper.valueToTree(value), mapper).toString();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw ApiException.badRequest("请求内容无法序列化");
        }
    }
    private static JsonNode canonical(JsonNode node, ObjectMapper mapper) {
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            ArrayList<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            keys.forEach(key -> result.set(key, canonical(node.get(key), mapper)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(item -> result.add(canonical(item, mapper)));
            return result;
        }
        return node;
    }
}
