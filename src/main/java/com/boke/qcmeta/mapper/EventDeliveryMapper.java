package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import com.boke.qcmeta.model.db.EventRows.*;
import com.boke.qcmeta.model.StoreModels.WebhookEvent;
import com.boke.qcmeta.model.ApiModels.ServiceEvent;
import com.fasterxml.jackson.databind.JsonNode;
@Mapper
public interface EventDeliveryMapper {
    List<ServiceEvent> lockAvailable(@Param("businessId") String businessId, @Param("limit") int limit);
    int lease(@Param("lease") Lease lease);
    List<Long> lockReceipt(@Param("receipt") Receipt receipt);
    int acknowledge(@Param("sequences") List<Long> sequences);
    int replay(@Param("businessId") String businessId, @Param("sequences") List<Long> sequences);
}
