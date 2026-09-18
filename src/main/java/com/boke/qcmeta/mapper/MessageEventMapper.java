package com.boke.qcmeta.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

@Mapper
public interface MessageEventMapper {
    int insertSnapshot(@Param("eventId") String eventId, @Param("messageId") String messageId);
    int insertSnapshots(@Param("snapshots") List<com.boke.qcmeta.model.db.MessageCommands.EventSnapshot> snapshots);
}
