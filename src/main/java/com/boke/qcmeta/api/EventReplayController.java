package com.boke.qcmeta.api;
import com.boke.qcmeta.model.ApiModels.*;
import com.boke.qcmeta.store.EventDeliveryService;
import org.springframework.web.bind.annotation.*;

@RestController
public class EventReplayController {
    private final EventDeliveryService events;
    public EventReplayController(EventDeliveryService events) { this.events=events; }
    @PostMapping("/admin/v1/events/replay")
    public AckEventsResponse replay(@RequestBody ReplayEventsRequest request) {
        return new AckEventsResponse(events.replay(request));
    }
}
