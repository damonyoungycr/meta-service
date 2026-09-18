package com.boke.qcmeta.api;

import com.boke.qcmeta.media.AssetProperties;
import com.boke.qcmeta.media.AssetService;
import com.boke.qcmeta.media.MediaRules;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {
    private final AssetService assets;
    private final AssetProperties properties;
    public AssetController(AssetService assets, AssetProperties properties) { this.assets=assets; this.properties=properties; }
    @PostMapping(consumes="multipart/form-data")
    public AssetService.Asset upload(@RequestParam String businessId,@RequestParam String senderId,@RequestParam String senderPhone,
                                     @RequestParam(defaultValue="2") int directoryType,@RequestPart MultipartFile file) throws IOException {
        long max = Math.min(properties.getMaxBytes(),MediaRules.maxBytes(file.getContentType()));
        if (max<=0 || file.getSize()>max) throw ApiException.badRequest("文件超过允许大小");
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes((int)Math.min(max+1,Integer.MAX_VALUE));
            return assets.upload(businessId,senderId,file.getOriginalFilename(),file.getContentType(),bytes,directoryType,senderPhone);
        }
    }
    @GetMapping("/{assetId}") public AssetService.Asset get(@RequestParam String businessId,@PathVariable String assetId) {
        return assets.get(businessId,assetId);
    }
    @PostMapping("/{assetId}/retry") public AssetService.Asset retry(@RequestParam String businessId,@PathVariable String assetId) {
        return assets.retry(businessId,assetId);
    }
    @PostMapping("/{assetId}/ycloud") public AssetService.Asset uploadToYCloud(@RequestParam String businessId,@PathVariable String assetId) {
        return assets.uploadToYCloud(businessId,assetId);
    }
}
