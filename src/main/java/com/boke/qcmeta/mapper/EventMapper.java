package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import com.boke.qcmeta.model.db.EventRows.*;
import com.boke.qcmeta.model.StoreModels.WebhookEvent;
import com.boke.qcmeta.model.StoreModels.MessageRecord;
import com.boke.qcmeta.model.ApiModels.ServiceEvent;
import com.fasterxml.jackson.databind.JsonNode;
@Mapper
public interface EventMapper {
    int insertProviderEvent(@Param("event") WebhookEvent event);
    int markProcessed(@Param("eventId") String eventId);
    List<Callback> pendingCallbacks();
    int markForwarded(@Param("eventId") String eventId);
    int deferCallback(@Param("eventId") String eventId, @Param("nextTime") Instant nextTime);
    List<String> pendingProviderEvents();
    Pending lockPending(@Param("eventId") String eventId);
    int deferProviderEvent(@Param("eventId") String eventId, @Param("nextTime") Instant nextTime);
    List<MessageRecord> findOwner(@Param("key") String key, @Param("value") String value);
    List<ServiceEvent> list(@Param("businessId") String businessId, @Param("afterSequence") long afterSequence, @Param("limit") int limit);
    long countPending();
    int deleteAcknowledged(@Param("before") Instant before);
}
