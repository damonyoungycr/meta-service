package com.boke.qcmeta.store;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.model.ApiModels.*;
import com.boke.qcmeta.mapper.EventDeliveryMapper;
import com.boke.qcmeta.model.db.EventRows.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventDeliveryService {
    private final EventDeliveryMapper database;
    private final TransactionTemplate transactions;
    public EventDeliveryService(EventDeliveryMapper database,TransactionTemplate transactions) {
        this.database = database;this.transactions=transactions;
    }
    public EventBatch pull(PullEventsRequest request) {
        requireId(request.businessId(),"businessId"); requireId(request.consumerId(),"consumerId");
        int limit=request.limit()==null?100:request.limit();
        if(limit<1 || limit>1000) throw ApiException.badRequest("limit 必须为 1 到 1000");
        return transactions.execute(tx -> {
            List<ServiceEvent> items=database.lockAvailable(request.businessId(), limit);
            String token=UUID.randomUUID().toString(); Instant until=Instant.now().plusSeconds(60);
            if (!items.isEmpty()) database.lease(new Lease(items.stream().map(ServiceEvent::sequence).toList(), token, request.consumerId(), until));
            return new EventBatch(token,until,items);
        });
    }
    public int confirm(ConfirmEventsRequest request) {
        requireId(request.businessId(),"businessId"); requireId(request.consumerId(),"consumerId");
        if(request.leaseToken()==null || request.leaseToken().isBlank()) throw ApiException.badRequest("leaseToken 不能为空");
        List<Long> ids=validSequences(request.sequences());
        return transactions.execute(tx -> {
            List<Long> matching=database.lockReceipt(new Receipt(ids, request.businessId(), request.consumerId(), request.leaseToken()));
            if (!matching.equals(ids)) throw ApiException.conflict("确认凭证不匹配，请重新领取事件");
            return database.acknowledge(ids);
        });
    }
    public int replay(ReplayEventsRequest request) {
        requireId(request.businessId(),"businessId");
        var ids=validSequences(request.sequences());
        return transactions.execute(tx -> database.replay(request.businessId(), ids));
    }
    private static List<Long> validSequences(List<Long> values) {
        if(values==null || values.isEmpty() || values.size()>1000 || values.stream().anyMatch(v->v==null || v<=0)) throw ApiException.badRequest("sequences 必须为 1 到 1000 个正整数");
        return values.stream().distinct().sorted().toList();
    }
    private static void requireId(String id,String field) { if(id==null || id.isBlank() || id.length()>128) throw ApiException.badRequest(field+" 必须为 1 到 128 个字符"); }
}
