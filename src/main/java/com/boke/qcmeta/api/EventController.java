package com.boke.qcmeta.api;

import com.boke.qcmeta.model.ApiModels.AckEventsRequest;
import com.boke.qcmeta.model.ApiModels.AckEventsResponse;
import com.boke.qcmeta.model.ApiModels.EventsView;
import com.boke.qcmeta.store.EventRepository;
import com.boke.qcmeta.store.EventDeliveryService;
import com.boke.qcmeta.model.ApiModels.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {
    private final EventRepository events;
    private final EventDeliveryService delivery;

    public EventController(EventRepository events,EventDeliveryService delivery) {
        this.events = events;
        this.delivery=delivery;
    }

    @PostMapping("/pull")
    public EventBatch pull(@RequestBody PullEventsRequest request) { return delivery.pull(request); }

    @PostMapping("/confirm")
    public AckEventsResponse confirm(@RequestBody ConfirmEventsRequest request) {
        return new AckEventsResponse(delivery.confirm(request));
    }

    @GetMapping
    public EventsView list(@RequestParam String businessId,@RequestParam(defaultValue = "0") long afterSequence,
                           @RequestParam(defaultValue = "100") int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 1000) {
            throw ApiException.badRequest("afterSequence 不能小于 0，limit 必须在 1 到 1000 之间");
        }
        return new EventsView(events.list(businessId,afterSequence, limit));
    }

    @PostMapping("/ack")
    public AckEventsResponse acknowledge(@RequestBody AckEventsRequest request) {
        return new AckEventsResponse(events.acknowledge(request == null ? null : request.sequences()));
    }
}
