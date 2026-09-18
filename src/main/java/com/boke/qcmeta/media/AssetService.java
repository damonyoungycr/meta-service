package com.boke.qcmeta.media;

import com.boke.qcmeta.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.boke.qcmeta.mapper.AssetMapper;
import com.boke.qcmeta.model.db.AssetCommands.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class AssetService {
    private final AssetMapper database;
    private final TransactionTemplate transactions;
    private final OssStorage storage;
    private final YCloudMediaClient ycloud;
    private final AssetProperties properties;
    private final ObjectMapper mapper;
    public AssetService(AssetMapper database, TransactionTemplate transactions,
                        OssStorage storage, YCloudMediaClient ycloud, AssetProperties properties, ObjectMapper mapper) {
        this.database = database; this.transactions=transactions; this.storage=storage;
        this.ycloud=ycloud; this.properties=properties; this.mapper=mapper;
    }
    public Asset upload(String businessId, String senderId, String filename, String mime, byte[] content, int type, String senderPhone) {
        validateScope(businessId, senderId);
        requirePhone(senderPhone);
        mime = MediaRules.normalize(mime);
        MediaRules.validate(content, mime, properties.getMaxBytes());
        OssStorage.StoredObject object = storage.put(content, mime, type);
        String id = UUID.randomUUID().toString();
        String safeFilename = MediaRules.safeFilename(filename, mime);
        String finalMime = mime;
        return transactions.execute(status -> {
            database.insertUpload(new Upload(id, businessId, senderId, safeFilename, finalMime, content.length, object.key(), object.url(), senderPhone));
            Asset asset = get(businessId,id);
            emit(asset, "media.ready");
            return asset;
        });
    }
    public Asset get(String businessId, String assetId) {
        requireId(businessId, "businessId"); requireId(assetId, "assetId");
        List<Asset> values = database.find(businessId, assetId);
        if (values.isEmpty()) throw ApiException.notFound("素材不存在或不属于该业务");
        return values.getFirst();
    }
    public Asset retry(String businessId, String assetId) {
        Asset asset = get(businessId, assetId);
        if (!"FAILED".equals(asset.status()) || asset.inboundMessageId().isBlank())
            throw ApiException.conflict("只有下载失败的入站素材可重试");
        database.retry(businessId, assetId);
        return get(businessId,assetId);
    }
    public Asset uploadToYCloud(String businessId, String assetId) {
        Asset asset = get(businessId,assetId);
        if (!"READY".equals(asset.status())) throw ApiException.conflict("素材尚未就绪");
        if (!asset.providerMediaId().isBlank() && asset.providerExpiresAt()!=null
                && asset.providerExpiresAt().isAfter(Instant.now().plusSeconds(300))) return asset;
        validateScope(businessId,asset.senderId());
        requirePhone(asset.senderPhone());
        String key = database.objectKey(assetId);
        byte[] bytes = storage.read(key, Math.min(properties.getMaxBytes(),MediaRules.maxBytes(asset.mimeType())));
        MediaRules.validate(bytes,asset.mimeType(),properties.getMaxBytes());
        String id = ycloud.upload(asset.senderPhone(),bytes,asset.filename(),asset.mimeType());
        database.setProvider(new Provider(assetId, businessId, id, Instant.now().plus(30,ChronoUnit.DAYS)));
        return get(businessId,assetId);
    }
    /** 给消息生成固定的 OSS 链接快照，避免平台媒体 ID 过期导致定时发送失败。 */
    public JsonNode resolveContent(String businessId, String senderId, String senderPhone, JsonNode content) {
        return contentResolver(businessId, senderId, senderPhone).apply(content);
    }
    public java.util.function.UnaryOperator<JsonNode> contentResolver(String businessId, String senderId, String senderPhone) {
        // 缓存只属于当前请求，不能把上一批的素材归属和状态带到下一批。
        var cache = new java.util.HashMap<String, Asset>();
        return content -> {
            JsonNode copy = content.deepCopy();
            resolveNode(businessId, senderId, senderPhone, copy, "", cache);
            return copy;
        };
    }
    private void resolveNode(String businessId, String senderId, String senderPhone, JsonNode node, String expectedType, java.util.Map<String, Asset> cache) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode)node;
            String ownType=object.path("type").asText("").toLowerCase(java.util.Locale.ROOT);
            String type=List.of("image","video","audio","document","sticker").contains(ownType)?ownType:expectedType;
            String assetId = object.path("assetId").asText("");
            if (!assetId.isBlank()) {
                Asset asset = cache.computeIfAbsent(assetId, id -> get(businessId, id));
                if (!asset.senderId().equals(senderId) || !asset.senderPhone().equals(senderPhone)) throw ApiException.badRequest("素材不属于当前发送号码");
                if (!"READY".equals(asset.status())) throw ApiException.conflict("素材尚未就绪，不能发送");
                boolean validType=switch(type) {
                    case "image" -> List.of("image/jpeg","image/png").contains(asset.mimeType());
                    case "sticker" -> "image/webp".equals(asset.mimeType());
                    case "video" -> asset.mimeType().startsWith("video/");
                    case "audio" -> asset.mimeType().startsWith("audio/");
                    case "document" -> asset.mimeType().startsWith("application/") || asset.mimeType().equals("text/plain");
                    default -> true;
                };
                if(!validType) throw ApiException.badRequest("素材 MIME 与消息媒体类型不一致");
                if (!object.path("id").asText("").isBlank() || !object.path("mediaId").asText("").isBlank()
                        || !object.path("link").asText("").isBlank())
                    throw ApiException.badRequest("assetId 与 mediaId/id/link 只能填写一种");
                object.remove("assetId"); object.put("link",asset.url());
            }
            object.fields().forEachRemaining(field -> resolveNode(businessId,senderId,senderPhone,field.getValue(),
                    List.of("image","video","audio","document","sticker").contains(field.getKey())?field.getKey():type, cache));
        } else if (node.isArray()) node.forEach(child -> resolveNode(businessId,senderId,senderPhone,child,expectedType, cache));
    }
    // 仅恢复升级前遗留的下载待办，新入站媒体由业务方处理，不必每秒空查。
    @Scheduled(fixedDelay = 30000)
    public void archivePending() {
        for (int i=0; i<4; i++) {
            Download job = claim(); if (job==null) return;
            try {
                long max = Math.min(properties.getMaxBytes(),MediaRules.maxBytes(job.asset().mimeType()));
                byte[] bytes = ycloud.download(job.sourceUrl(),max);
                MediaRules.validate(bytes,job.asset().mimeType(),properties.getMaxBytes());
                OssStorage.StoredObject object = storage.put(bytes,job.asset().mimeType(),4);
                transactions.executeWithoutResult(status -> {
                    int updated = database.completeDownload(new Downloaded(job.asset().assetId(), job.claimId(), object.key(), object.url(), bytes.length));
                    if (updated>0) emit(get(job.asset().businessId(),job.asset().assetId()),"media.ready");
                });
            } catch (RuntimeException exception) {
                transactions.executeWithoutResult(status -> {
                    String code = exception instanceof ApiException api && api.status().value()==400 ? "INVALID_MEDIA" : "DOWNLOAD_FAILED";
                    int updated = database.failDownload(job.asset().assetId(), job.claimId(), code);
                    if (updated>0) emit(get(job.asset().businessId(),job.asset().assetId()),"media.failed");
                });
            }
        }
    }
    private Download claim() {
        return transactions.execute(status -> {
            List<Asset> pending = database.lockPending();
            if (pending.isEmpty()) return null;
            Asset asset = pending.getFirst(); String claim = UUID.randomUUID().toString();
            database.claim(asset.assetId(), claim, Instant.now().plusSeconds(300));
            String source = database.sourceUrl(asset.assetId());
            return new Download(asset,source,claim);
        });
    }
    private void emit(Asset asset, String type) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("schemaVersion",1); payload.put("assetId",asset.assetId()); payload.put("inboundMessageId",asset.inboundMessageId());
        payload.put("status",asset.status()); payload.put("url",asset.url()); payload.put("mimeType",asset.mimeType());
        payload.put("errorCode",asset.errorCode());
        database.insertEvent(new com.boke.qcmeta.model.ApiModels.ServiceEvent(0, UUID.randomUUID().toString(), type, asset.businessId(), asset.senderId(), asset.assetId(), payload, Instant.now()));
    }
    public void validateScope(String businessId, String senderId) {
        requireId(businessId,"businessId"); requireId(senderId,"senderId");
    }
    private static void requirePhone(String phone) {
        if (phone==null || !phone.matches("\\+[1-9][0-9]{7,14}")) throw ApiException.badRequest("senderPhone 必须为带 + 的 E.164 发送号码");
    }
    private static void requireId(String id, String field) {
        if (id==null || id.isBlank() || id.length()>128) throw ApiException.badRequest(field + " 不能为空且最长 128 字符");
    }
    public record Asset(String assetId,String businessId,String senderId,String status,String filename,String mimeType,
                        long sizeBytes,String url,String providerMediaId,Instant providerExpiresAt,String inboundMessageId,
                        String errorCode,Instant createdAt,Instant updatedAt,String senderPhone) {}
    private record Download(Asset asset,String sourceUrl,String claimId) {}
}
