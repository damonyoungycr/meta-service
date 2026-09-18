package com.boke.qcmeta.grpc;

import com.boke.qcmeta.grpc.v1.*;
import com.boke.qcmeta.campaign.CampaignApplicationService;
import com.boke.qcmeta.campaign.CampaignModels;
import com.boke.qcmeta.model.MessageContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class GrpcCampaignService extends CampaignServiceGrpc.CampaignServiceImplBase {
    private final CampaignApplicationService campaigns;
    private final ObjectMapper mapper;

    public GrpcCampaignService(CampaignApplicationService campaigns, ObjectMapper mapper) {
        this.campaigns = campaigns;
        this.mapper = mapper;
    }

    @Override
    public void createCampaign(CreateCampaignRequest request, StreamObserver<Campaign> observer) {
        GrpcSupport.respond(observer, () -> view(campaigns.create(new CampaignModels.CreateRequest(
                request.getCampaignId(),request.getBusinessId(),request.getSenderId(),request.getName(),
                request.getTemplateName(),request.getLanguageCode(),request.getRecipientsList().stream().map(row -> {
                    var message=SubmitMessageRequest.newBuilder().setContent(com.boke.qcmeta.grpc.v1.MessageContent.newBuilder()
                            .setKind(MessageKind.MESSAGE_KIND_TEMPLATE).setTemplate(TemplateContent.newBuilder()
                            .setName(row.getTemplateName()).setLanguageCode(row.getLanguageCode()).addAllComponents(row.getComponentsList()))).build();
                    MessageContent.TemplateContent template=GrpcConverters.submitRequest(message,mapper).content().template();
                    return new CampaignModels.Recipient(row.getRecipientId(),row.getCustomerId(),GrpcConverters.recipient(row.getRecipientType()),
                            row.getRecipient(),template.components(),row.getTemplateName(),row.getLanguageCode());
                }).toList(),GrpcConverters.instant(request.hasSendAt()?request.getSendAt():null),
                request.hasFilterUnsubscribed()?request.getFilterUnsubscribed():null,
                request.hasFilterBlocked()?request.getFilterBlocked():null,request.getSourceId(),request.getSenderPhone(),request.getWabaId()))));
    }

    @Override
    public void getCampaign(GetCampaignRequest request, StreamObserver<Campaign> observer) {
        GrpcSupport.respond(observer, () ->
                view(campaigns.get(request.getBusinessId(), request.getCampaignId())));
    }

    @Override
    public void cancelCampaign(CancelCampaignRequest request, StreamObserver<Campaign> observer) {
        GrpcSupport.respond(observer, () ->
                view(campaigns.cancel(request.getBusinessId(), request.getCampaignId())));
    }

    private Campaign view(CampaignModels.View value) {
        return Campaign.newBuilder().setCampaignId(value.campaignId()).setBusinessId(value.businessId()).setSenderId(value.senderId())
                .setName(value.name()).setState(value.state()).setTotal(value.total()).setPending(value.pending()).setSubmitting(value.submitting())
                .setAccepted(value.accepted()).setSent(value.sent()).setDelivered(value.delivered()).setRead(value.read()).setFailed(value.failed())
                .setUnknown(value.unknown()).setCancelled(value.cancelled()).setProcessed(value.processed()).setTaskFinished(value.taskFinished())
                .setDuplicatedRequest(value.duplicatedRequest()).setCreatedAt(GrpcConverters.timestamp(value.createdAt())).build();
    }
}
