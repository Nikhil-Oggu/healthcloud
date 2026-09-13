package com.healthcloud.consent;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consent directive API, nested under the patient it concerns (source-of-truth §22). Thin controller
 * (§31.5): tenant scoping, authorization, and the supersede/revoke transactions live in
 * {@link ConsentDirectiveService}. Every route is authenticated and tenant-scoped (a patient in another
 * tenant is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/consent-directives")
public class ConsentDirectiveController {

    private final ConsentDirectiveService service;
    private final ConsentPolicyService policyService;

    public ConsentDirectiveController(ConsentDirectiveService service, ConsentPolicyService policyService) {
        this.service = service;
        this.policyService = policyService;
    }

    /** A patient's consent directives — the current set by default, or every version with includeHistory. */
    @GetMapping
    public List<ConsentDirectiveDto> list(
            @PathVariable UUID patientId,
            @RequestParam(name = "includeHistory", defaultValue = "false") boolean includeHistory) {
        return service.list(patientId, includeHistory);
    }

    /** Record a consent directive (supersedes the current one for the same natural key, if any). */
    @PostMapping
    public ResponseEntity<ConsentDirectiveDto> record(
            @PathVariable UUID patientId, @Valid @RequestBody RecordConsentRequest request) {
        ConsentDirectiveDto created = service.record(patientId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/consent-directives/" + created.id()))
                .body(created);
    }

    /** Revoke a specific current directive with immediate effect (optimistic-locked). */
    @PostMapping("/{directiveId}/revoke")
    public ConsentDirectiveDto revoke(
            @PathVariable UUID patientId, @PathVariable UUID directiveId,
            @Valid @RequestBody RevokeConsentRequest request) {
        return service.revoke(patientId, directiveId, request);
    }

    /**
     * The effective CONSENT decision (§22.5) for the calling actor accessing {@code dataCategory} for
     * {@code purpose} — GRANT/DENY plus which directive decided. This is the consent layer only, not the
     * full authorization decision (which also weighs role, relationship and business need).
     */
    @GetMapping("/decision")
    public ConsentDecisionDto decision(
            @PathVariable UUID patientId,
            @RequestParam ConsentPurpose purpose,
            @RequestParam ConsentDataCategory dataCategory) {
        return policyService.decide(patientId, purpose, dataCategory);
    }
}
