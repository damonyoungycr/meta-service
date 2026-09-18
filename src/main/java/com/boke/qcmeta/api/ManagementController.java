package com.boke.qcmeta.api;

import com.boke.qcmeta.management.ManagementModels.DisplayNameInput;
import com.boke.qcmeta.management.ManagementModels.TemplateInput;
import com.boke.qcmeta.management.ManagementService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class ManagementController {
    private final ManagementService service;
    public ManagementController(ManagementService service) { this.service = service; }

    @GetMapping("/admin/v1/wabas")
    public JsonNode wabas(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.listWabas(page, limit);
    }
    @GetMapping("/admin/v1/wabas/{wabaId}")
    public JsonNode waba(@PathVariable String wabaId) { return service.getWaba(wabaId); }
    @GetMapping("/admin/v1/wabas/{wabaId}/phone-numbers")
    public JsonNode phones(@PathVariable String wabaId, @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int limit) { return service.listPhones(wabaId, page, limit); }
    @GetMapping("/admin/v1/wabas/{wabaId}/phone-numbers/{phoneNumber}")
    public JsonNode phone(@PathVariable String wabaId, @PathVariable String phoneNumber) { return service.getPhone(wabaId, phoneNumber); }
    @PostMapping("/admin/v1/wabas/{wabaId}/phone-number-ids/{phoneNumberId}/register")
    public JsonNode register(@PathVariable String wabaId, @PathVariable String phoneNumberId) {
        return service.registerPhone(wabaId, phoneNumberId);
    }
    @GetMapping("/admin/v1/wabas/{wabaId}/phone-numbers/{phoneNumber}/profile")
    public JsonNode profile(@PathVariable String wabaId, @PathVariable String phoneNumber) { return service.getProfile(wabaId, phoneNumber); }
    @PatchMapping("/admin/v1/wabas/{wabaId}/phone-numbers/{phoneNumber}/profile")
    public JsonNode updateProfile(@PathVariable String wabaId, @PathVariable String phoneNumber, @RequestBody JsonNode input) {
        return service.updateProfile(wabaId, phoneNumber, input);
    }
    @PatchMapping("/admin/v1/wabas/{wabaId}/phone-numbers/{phoneNumber}/display-name")
    public JsonNode updateDisplayName(@PathVariable String wabaId, @PathVariable String phoneNumber, @RequestBody DisplayNameInput input) {
        if (input == null) throw ApiException.badRequest("newName 不能为空");
        return service.updateDisplayName(wabaId, phoneNumber, input.newName());
    }
    @GetMapping("/admin/v1/templates")
    public JsonNode templates(@RequestParam String wabaId, @RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String name,
                             @RequestParam(required = false) String language, @RequestParam(required = false) String status) {
        return service.listTemplates(wabaId, page, limit, name, language, status);
    }
    @GetMapping("/admin/v1/templates/{wabaId}/{name}/{language}")
    public JsonNode template(@PathVariable String wabaId, @PathVariable String name, @PathVariable String language) {
        return service.getTemplate(wabaId, name, language);
    }
    @PostMapping("/admin/v1/templates")
    public JsonNode createTemplate(@RequestBody TemplateInput input) { return service.createTemplate(input); }
    @PutMapping("/admin/v1/templates/{wabaId}/{name}/{language}")
    public JsonNode updateTemplate(@PathVariable String wabaId, @PathVariable String name, @PathVariable String language,
                                   @RequestBody TemplateInput input) { return service.updateTemplate(wabaId, name, language, input); }
    @DeleteMapping("/admin/v1/templates/{wabaId}/{name}/{language}")
    public JsonNode deleteTemplate(@PathVariable String wabaId, @PathVariable String name, @PathVariable String language) {
        return service.deleteTemplate(wabaId, name, language);
    }
    @GetMapping("/api/v1/templates")
    public JsonNode usableTemplates(@RequestParam String wabaId,
                                    @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit) {
        return service.listTemplates(wabaId, page, limit, null, null, "APPROVED");
    }
    @GetMapping("/api/v1/templates/{name}/{language}")
    public JsonNode usableTemplate(@PathVariable String name, @PathVariable String language,
                                   @RequestParam String wabaId) {
        return service.usableTemplate(wabaId, name, language);
    }
}
