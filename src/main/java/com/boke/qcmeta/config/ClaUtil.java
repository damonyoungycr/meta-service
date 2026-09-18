package com.boke.qcmeta.config;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

/** 沿用旧服务的加密格式，供 OSS 文件和数据库敏感配置共同使用。 */
public final class ClaUtil {
    // 固定密钥和 IV 必须与旧工具一致，否则现有配置文件无法读取。
    private static final String KEY = "823^7viSPKuio48@";

    private ClaUtil() {}

    public static String decrypt(String data) {
        try {
            String encoded = data.strip();
            byte[] encrypted = encoded.matches("(?i)[0-9a-f]+") && encoded.length() % 32 == 0
                    ? HexFormat.of().parseHex(encoded) : Base64.getDecoder().decode(encoded);
            return new String(cipher(Cipher.DECRYPT_MODE).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 不附带原文或底层异常，防止配置内容出现在启动日志中。
            throw new IllegalArgumentException("CLA 配置无法解密，请检查文件格式");
        }
    }

    public static String encryptStr(String content) {
        try {
            return Base64.getEncoder().encodeToString(
                    cipher(Cipher.ENCRYPT_MODE).doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("CLA 配置加密失败");
        }
    }

    private static Cipher cipher(int mode) throws GeneralSecurityException {
        byte[] key = KEY.getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return cipher;
    }
}
