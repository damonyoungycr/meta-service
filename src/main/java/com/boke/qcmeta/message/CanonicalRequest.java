package com.boke.qcmeta.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

public final class CanonicalRequest {
    private CanonicalRequest() { }

    public static String message(com.boke.qcmeta.model.ApiModels.SubmitMessageRequest request,
                                 com.fasterxml.jackson.databind.ObjectMapper mapper) {
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(request);
        for(String field:java.util.List.of("sendRequestId","businessId","senderId","customerId","recipient","sourceId","senderPhone","wabaId"))
            if(node.path(field).isTextual()) node.put(field,node.path(field).asText().trim());
        for(String field:java.util.List.of("recipientType","deliveryMode"))
            node.put(field,node.path(field).asText("").trim().toUpperCase(java.util.Locale.ROOT));
        normalizeDefaults(node);
        return hash(node);
    }
    private static void normalizeDefaults(JsonNode node) {
        if(node.isObject()) {
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;
            var fields=new java.util.ArrayList<String>();node.fieldNames().forEachRemaining(fields::add);
            for(String name:fields) {
                JsonNode value=node.get(name);
                if(value.isNull() || (value.isTextual() && value.asText().isEmpty())
                        || (name.equals("previewUrl") && value.isBoolean() && !value.asBoolean())) object.remove(name);
                else if(java.util.Set.of("type","subType").contains(name) && value.isTextual()) object.put(name,value.asText().trim().toLowerCase(java.util.Locale.ROOT));
                else normalizeDefaults(value);
            }
        } else if(node.isArray()) node.forEach(CanonicalRequest::normalizeDefaults);
    }

    public static String hash(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(field -> sorted.put(field.getKey(), canonical(field.getValue())));
            return JsonNodeFactory.instance.objectNode().setAll(sorted);
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(canonical(value)));
            return result;
        }
        return node;
    }
}
