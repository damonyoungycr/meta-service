package com.boke.qcmeta.media;

import com.boke.qcmeta.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public final class MediaRules {
    private MediaRules() {}
    private static final Map<String,String> EXTENSIONS = Map.ofEntries(
        Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/webp", "webp"),
        Map.entry("video/mp4", "mp4"), Map.entry("video/3gpp", "3gp"),
        Map.entry("audio/aac", "aac"), Map.entry("audio/amr", "amr"), Map.entry("audio/mpeg", "mp3"),
        Map.entry("audio/mp4", "m4a"), Map.entry("audio/ogg", "ogg"),
        Map.entry("application/pdf", "pdf"), Map.entry("text/plain", "txt"),
        Map.entry("application/msword", "doc"), Map.entry("application/vnd.ms-excel", "xls"),
        Map.entry("application/vnd.ms-powerpoint", "ppt"),
        Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
        Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"));
    public static String normalize(String mime) {
        String value = mime == null ? "" : mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(value) ? "image/jpeg" : value;
    }
    public static String extension(String mime) {
        String extension = EXTENSIONS.get(normalize(mime));
        if (extension == null) throw ApiException.badRequest("不支持的媒体 MIME 类型");
        return extension;
    }
    public static long maxBytes(String mime) {
        String type = normalize(mime);
        if (type.equals("image/webp")) return 500 * 1024;
        if (type.startsWith("image/")) return 5 * 1024 * 1024;
        if (type.startsWith("audio/") || type.startsWith("video/")) return 16 * 1024 * 1024;
        return 100L * 1024 * 1024;
    }
    public static String safeFilename(String filename, String mime) {
        String value = filename == null ? "" : filename.replace('\\','/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\";]", "_");
        if (value.isBlank()) value = "media." + extension(mime);
        return value.substring(0, Math.min(value.length(), 200));
    }
    public static void validate(byte[] bytes, String mime, long configuredMax) {
        String type = normalize(mime);
        extension(type);
        if (configuredMax <= 0 || bytes.length == 0 || bytes.length > Math.min(configuredMax, maxBytes(type)))
            throw ApiException.badRequest("文件为空或超过该媒体类型的大小限制");
        if (type.equals("image/webp") && bytes.length > 100 * 1024
                && !(bytes.length > 20 && text(bytes,12,"VP8X") && (bytes[20]&2)!=0))
            throw ApiException.badRequest("静态 WebP 贴纸不能超过 100 KiB");
        boolean matches = switch (type) {
            case "image/png" -> starts(bytes, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "image/jpeg" -> starts(bytes, 0xff, 0xd8, 0xff);
            case "image/webp" -> text(bytes, 0, "RIFF") && text(bytes, 8, "WEBP");
            case "application/pdf" -> text(bytes, 0, "%PDF-");
            case "video/mp4", "video/3gpp", "audio/mp4" -> text(bytes, 4, "ftyp");
            case "audio/ogg" -> text(bytes, 0, "OggS");
            case "audio/amr" -> text(bytes, 0, "#!AMR");
            case "audio/mpeg" -> text(bytes, 0, "ID3") || (bytes.length > 1 && (bytes[0]&255)==255 && (bytes[1]&224)==224);
            case "audio/aac" -> bytes.length > 1 && (bytes[0]&255)==255 && (bytes[1]&240)==240;
            case "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint" -> starts(bytes, 0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1);
            case "text/plain" -> !new String(bytes, StandardCharsets.UTF_8).contains("\u0000");
            default -> starts(bytes, 0x50, 0x4b, 0x03, 0x04);
        };
        if (!matches) throw ApiException.badRequest("文件内容与声明的 MIME 类型不符");
    }
    private static boolean text(byte[] value, int offset, String expected) {
        byte[] prefix = expected.getBytes(StandardCharsets.US_ASCII);
        if (value.length < offset + prefix.length) return false;
        for (int i=0; i<prefix.length; i++) if (value[offset+i] != prefix[i]) return false;
        return true;
    }
    private static boolean starts(byte[] value, int... expected) {
        if (value.length < expected.length) return false;
        for (int i=0; i<expected.length; i++) if ((value[i]&255) != expected[i]) return false;
        return true;
    }
}
