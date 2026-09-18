package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import com.boke.qcmeta.model.StoreModels.*;
import com.boke.qcmeta.model.db.MessageCommands.*;
import com.fasterxml.jackson.databind.JsonNode;
@Mapper
public interface MessageMapper {
    int insertOutbox(@Param("message") NewMessage message);
    int insertMessage(@Param("message") NewMessage message);
    int insertMessages(@Param("messages") List<NewMessage> messages);
    int insertOutboxes(@Param("messages") List<NewMessage> messages);
    List<MessageRecord> findByRequest(@Param("businessId") String businessId, @Param("requestId") String requestId);
    String fingerprint(@Param("messageId") String messageId);
    MessageRecord lockMessage(@Param("messageId") String messageId);
    int applyProviderResult(@Param("change") ProviderResult change);
    List<String> resolveReply(@Param("query") Reply query);
    MessageRecord find(@Param("messageId") String messageId);
    List<OutboxRecord> lockReadyOutbox(@Param("limit") int limit);
    int claimOutbox(@Param("outboxIds") List<Long> outboxIds, @Param("owner") String owner, @Param("lockedUntil") Instant lockedUntil);
    int publishOutbox(@Param("outboxId") long outboxId, @Param("owner") String owner);
    int retryOutbox(@Param("change") OutboxRetry change);
    int recoverOutbox();
    int claimMessage(@Param("messageId") String messageId, @Param("owner") String owner, @Param("lockedUntil") Instant lockedUntil);
    List<String> lockExpiredMessages();
    int insertAttempt(@Param("attempt") AttemptRecord attempt);
    int failMessage(@Param("change") Failure change);
    int markUnknown(@Param("change") Failure change);
    int retryMessage(@Param("change") Failure change);
    int resetOutbox(@Param("messageId") String messageId, @Param("nextTime") Instant nextTime);
    int cancelCampaign(@Param("campaignId") String campaignId);
    List<String> lockCampaignMessages(@Param("campaignId") String campaignId);
    int cancelCampaignMessages(@Param("campaignId") String campaignId);
    int cancelCampaignOutbox(@Param("campaignId") String campaignId);
    long countPendingOutbox();
}
