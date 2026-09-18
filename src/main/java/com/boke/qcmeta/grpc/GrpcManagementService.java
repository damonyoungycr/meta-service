package com.boke.qcmeta.grpc;

import com.boke.qcmeta.api.ApiException;
import com.boke.qcmeta.grpc.v1.*;
import com.boke.qcmeta.management.ManagementModels.TemplateInput;
import com.boke.qcmeta.management.ManagementService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class GrpcManagementService extends ManagementServiceGrpc.ManagementServiceImplBase {
    private final ManagementService service;
    private final ObjectMapper mapper;
    public GrpcManagementService(ManagementService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @Override public void listWabas(ManagementPageRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.listWabas(page(r.getPage()), limit(r.getLimit()))); }
    @Override public void getWaba(WabaKey r, StreamObserver<ManagementJson> o) { respond(o, () -> service.getWaba(r.getWabaId())); }
    @Override public void listPhoneNumbers(PhoneListRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.listPhones(r.getWabaId(), page(r.getPage()), limit(r.getLimit()))); }
    @Override public void getPhoneNumber(PhoneKey r, StreamObserver<ManagementJson> o) { respond(o, () -> service.getPhone(r.getWabaId(), r.getPhoneNumber())); }
    @Override public void registerPhoneNumber(RegisterPhoneRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.registerPhone(r.getWabaId(), r.getPhoneNumberId())); }
    @Override public void getPhoneProfile(PhoneKey r, StreamObserver<ManagementJson> o) { respond(o, () -> service.getProfile(r.getWabaId(), r.getPhoneNumber())); }
    @Override public void updatePhoneProfile(UpdatePhoneProfileRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.updateProfile(r.getKey().getWabaId(), r.getKey().getPhoneNumber(), parse(r.getProfileJson()))); }
    @Override public void updatePhoneDisplayName(UpdatePhoneDisplayNameRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.updateDisplayName(r.getKey().getWabaId(), r.getKey().getPhoneNumber(), r.getNewName())); }
    @Override public void listTemplates(TemplateListRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.listTemplates(r.getWabaId(), page(r.getPage()), limit(r.getLimit()), r.getName(), r.getLanguage(), r.getStatus())); }
    @Override public void getTemplate(TemplateKey r, StreamObserver<ManagementJson> o) { respond(o, () -> service.getTemplate(r.getWabaId(), r.getName(), r.getLanguage())); }
    @Override public void createTemplate(WriteTemplateRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.createTemplate(input(r))); }
    @Override public void updateTemplate(WriteTemplateRequest r, StreamObserver<ManagementJson> o) { respond(o, () -> service.updateTemplate(r.getKey().getWabaId(), r.getKey().getName(), r.getKey().getLanguage(), input(r))); }
    @Override public void deleteTemplate(TemplateKey r, StreamObserver<ManagementJson> o) { respond(o, () -> service.deleteTemplate(r.getWabaId(), r.getName(), r.getLanguage())); }
    private TemplateInput input(WriteTemplateRequest r) {
        return new TemplateInput(r.getKey().getWabaId(), r.getKey().getName(), r.getKey().getLanguage(), r.getCategory(),
                r.hasMessageSendTtlSeconds() ? r.getMessageSendTtlSeconds() : null, parse(r.getComponentsJson()),
                r.getSubCategory(), r.hasCtaUrlLinkTrackingOptedOut() ? r.getCtaUrlLinkTrackingOptedOut() : null);
    }
    private JsonNode parse(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw ApiException.badRequest("JSON 内容无效"); } }
    private void respond(StreamObserver<ManagementJson> o, Supplier<Object> action) {
        GrpcSupport.respond(o, () -> ManagementJson.newBuilder().setJson(mapper.valueToTree(action.get()).toString()).build());
    }
    static int page(int value) { return value == 0 ? 1 : value; }
    static int limit(int value) { return value == 0 ? 20 : value; }
}

@Component
class GrpcTemplateCatalogService extends TemplateCatalogServiceGrpc.TemplateCatalogServiceImplBase {
    private final ManagementService service;
    GrpcTemplateCatalogService(ManagementService service) { this.service = service; }
    @Override public void listUsableTemplates(UsableTemplatesRequest r, StreamObserver<ManagementJson> o) {
        GrpcSupport.respond(o, () -> {
            return ManagementJson.newBuilder().setJson(service.listTemplates(r.getWabaId(), GrpcManagementService.page(r.getPage()),
                    GrpcManagementService.limit(r.getLimit()), null, null, "APPROVED").toString()).build();
        });
    }
    @Override public void getUsableTemplate(UsableTemplateRequest r, StreamObserver<ManagementJson> o) {
        GrpcSupport.respond(o, () -> ManagementJson.newBuilder().setJson(service.usableTemplate(
                r.getWabaId(), r.getName(), r.getLanguage()).toString()).build());
    }
}
