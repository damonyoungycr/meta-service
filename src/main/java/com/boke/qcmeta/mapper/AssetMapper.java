package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import com.boke.qcmeta.media.AssetService.Asset;
import com.boke.qcmeta.model.db.AssetCommands.*;
import com.boke.qcmeta.model.ApiModels.ServiceEvent;
@Mapper
public interface AssetMapper {
    int insertUpload(@Param("asset") Upload asset);
    List<Asset> find(@Param("businessId") String businessId, @Param("assetId") String assetId);
    int retry(@Param("businessId") String businessId, @Param("assetId") String assetId);
    String objectKey(@Param("assetId") String assetId);
    int setProvider(@Param("change") Provider change);
    int completeDownload(@Param("asset") Downloaded asset);
    int failDownload(@Param("assetId") String assetId, @Param("claimId") String claimId, @Param("code") String code);
    List<Asset> lockPending();
    int claim(@Param("assetId") String assetId, @Param("claimId") String claimId, @Param("until") Instant until);
    String sourceUrl(@Param("assetId") String assetId);
    int insertEvent(@Param("event") ServiceEvent event);
}
