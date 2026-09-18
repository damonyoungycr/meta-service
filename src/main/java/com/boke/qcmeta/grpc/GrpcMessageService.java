package com.boke.qcmeta.grpc;

import com.boke.qcmeta.grpc.v1.GetMessageRequest;
import com.boke.qcmeta.grpc.v1.MessageServiceGrpc;
import com.boke.qcmeta.grpc.v1.SubmitMessageRequest;
import com.boke.qcmeta.grpc.v1.SubmitMessageResponse;
import com.boke.qcmeta.message.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GrpcMessageService extends MessageServiceGrpc.MessageServiceImplBase {
    private final MessageService messages;
    private final ObjectMapper objectMapper;

    public GrpcMessageService(MessageService messages, ObjectMapper objectMapper) {
        this.messages = messages;
        this.objectMapper = objectMapper;
    }

    @Override
    public void submitMessage(SubmitMessageRequest request,
                              StreamObserver<SubmitMessageResponse> observer) {
        GrpcSupport.respond(observer, () -> GrpcConverters.submitResponse(
                messages.submit(GrpcConverters.submitRequest(request, objectMapper))));
    }

    @Override
    public void getMessage(GetMessageRequest request,
                           StreamObserver<com.boke.qcmeta.grpc.v1.Message> observer) {
        GrpcSupport.respond(observer, () -> GrpcConverters.message(messages.get(request.getBusinessId(),request.getMessageId())));
    }
    @Override
    public void getMessageByRequest(com.boke.qcmeta.grpc.v1.GetMessageByRequestRequest request,
            StreamObserver<com.boke.qcmeta.grpc.v1.Message> observer) {
        GrpcSupport.respond(observer,()->GrpcConverters.message(messages.getByRequest(request.getBusinessId(),request.getSendRequestId())));
    }
    @Override
    public void reconcileMessage(GetMessageRequest request,StreamObserver<com.boke.qcmeta.grpc.v1.Message> observer) {
        GrpcSupport.respond(observer,()->GrpcConverters.message(messages.reconcile(request.getBusinessId(),request.getMessageId())));
    }
}
