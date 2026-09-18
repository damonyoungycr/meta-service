package com.boke.qcmeta.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 按 YCloud 规则校验 Webhook 签名和时间戳。
 *
 * <p>签名比较使用固定时间算法，避免普通字符串比较暴露多少位已经匹配。时间差超过配置范围的请求
 * 也会拒绝，用来减少旧请求被重复利用的机会。</p>
 */
@Component
public class WebhookVerifier {
    public boolean verify(byte[] rawBody, String header, String secret,
                          Duration tolerance, Instant now) {
        if (secret == null || secret.isBlank() || header == null || header.isBlank()) {
            return false;
        }
        String timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || pair[1].isBlank()) continue;
            if (pair[0].equals("t")) timestamp = pair[1];
            else if (pair[0].equals("s")) signatures.add(pair[1]);
        }
        if (timestamp == null || signatures.isEmpty()) return false;
        try {
            Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
            if (Duration.between(signedAt, now).abs().compareTo(tolerance) > 0) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            byte[] expected = mac.doFinal(rawBody);
            for (String signature : signatures) {
                try {
                    if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature))) return true;
                } catch (IllegalArgumentException ignored) {
                    // 尝试同一个请求头里的下一份签名。
                }
            }
            return false;
        } catch (GeneralSecurityException | NumberFormatException exception) {
            return false;
        }
    }
}
