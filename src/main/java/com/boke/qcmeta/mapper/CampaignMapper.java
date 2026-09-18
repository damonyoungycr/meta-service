package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import com.boke.qcmeta.model.db.CampaignRows.*;
@Mapper
public interface CampaignMapper {
    List<Fingerprint> fingerprint(@Param("campaignId") String campaignId);
    int insert(@Param("campaign") Create campaign);
    List<Statistics> statistics(@Param("businessId") String businessId, @Param("campaignId") String campaignId);
    List<String> lock(@Param("businessId") String businessId, @Param("campaignId") String campaignId);
}
