package com.boke.qcmeta.api;

import com.boke.qcmeta.message.MessageService;
import com.boke.qcmeta.model.ApiModels.MessageView;
import com.boke.qcmeta.model.ApiModels.SubmitMessageRequest;
import com.boke.qcmeta.model.ApiModels.SubmitMessageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @PostMapping
    public ResponseEntity<SubmitMessageResponse> submit(@RequestBody SubmitMessageRequest request) {
        SubmitMessageResponse response = messages.submit(request);
        return ResponseEntity.status(response.duplicatedRequest() ? 200 : 202).body(response);
    }

    @GetMapping("/{messageId}")
    public MessageView get(@PathVariable String messageId,@RequestParam String businessId) {
        return messages.get(businessId,messageId);
    }

    @GetMapping("/by-request")
    public MessageView byRequest(@RequestParam String businessId,@RequestParam String sendRequestId) {
        return messages.getByRequest(businessId,sendRequestId);
    }

    @PostMapping("/{messageId}/reconcile")
    public MessageView reconcile(@PathVariable String messageId,@RequestParam String businessId) {
        return messages.reconcile(businessId,messageId);
    }
}
