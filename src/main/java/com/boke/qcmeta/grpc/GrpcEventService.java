package com.boke.qcmeta.grpc;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.grpc.v1.*;
import com.boke.qcmeta.store.EventRepository;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GrpcEventService extends EventServiceGrpc.EventServiceImplBase {
    private final EventRepository events;
    private final com.boke.qcmeta.store.EventDeliveryService delivery;

    public GrpcEventService(EventRepository events,com.boke.qcmeta.store.EventDeliveryService delivery) {
        this.events = events;
        this.delivery=delivery;
    }
    @Override
    public void pullEvents(PullEventsRequest request,StreamObserver<EventBatch> observer) {
        GrpcSupport.respond(observer,()->{
            var value=delivery.pull(new com.boke.qcmeta.model.ApiModels.PullEventsRequest(request.getBusinessId(),request.getConsumerId(),request.getLimit()==0?100:request.getLimit()));
            var builder=EventBatch.newBuilder().setLeaseToken(value.leaseToken()).setLeasedUntil(GrpcConverters.timestamp(value.leasedUntil()));
            value.items().forEach(event->builder.addEvents(GrpcConverters.event(event)));
            return builder.build();
        });
    }
    @Override
    public void confirmEvents(ConfirmEventsRequest request,StreamObserver<AckEventsResponse> observer) {
        GrpcSupport.respond(observer,()->AckEventsResponse.newBuilder().setAcknowledged(delivery.confirm(
                new com.boke.qcmeta.model.ApiModels.ConfirmEventsRequest(request.getBusinessId(),request.getConsumerId(),request.getLeaseToken(),request.getSequencesList()))).build());
    }
    @Override
    public void replayEvents(ReplayEventsRequest request,StreamObserver<AckEventsResponse> observer) {
        GrpcSupport.respond(observer,()->AckEventsResponse.newBuilder().setAcknowledged(delivery.replay(
                new com.boke.qcmeta.model.ApiModels.ReplayEventsRequest(request.getBusinessId(),request.getSequencesList()))).build());
    }

    @Override
    public void listEvents(ListEventsRequest request, StreamObserver<ListEventsResponse> observer) {
        GrpcSupport.respond(observer, () -> {
            int limit = request.getLimit() <= 0 ? 100 : request.getLimit();
            if (request.getAfterSequence() < 0 || limit > 1000) {
                throw ApiException.badRequest("afterSequence 不能小于 0，limit 不能超过 1000");
            }
            ListEventsResponse.Builder builder = ListEventsResponse.newBuilder();
            events.list(request.getBusinessId(),request.getAfterSequence(), limit).forEach(value ->
                    builder.addEvents(GrpcConverters.event(value)));
            return builder.build();
        });
    }

    @Override
    public void ackEvents(AckEventsRequest request, StreamObserver<AckEventsResponse> observer) {
        GrpcSupport.respond(observer, () -> AckEventsResponse.newBuilder()
                .setAcknowledged(events.acknowledge(request.getSequencesList())).build());
    }
}
