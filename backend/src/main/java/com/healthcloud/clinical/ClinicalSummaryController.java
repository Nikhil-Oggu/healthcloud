package com.healthcloud.clinical;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * Clinical summary API, nested under the patient it concerns (source-of-truth §Phase 4). Thin controller
 * (§31.5): tenant scoping, the object/relationship gate, consent masking and diagnosis-code validation all
 * live in {@link ClinicalSummaryService}. Every route is authenticated and tenant-scoped (a patient in another
 * tenant is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/clinical-summaries")
public class ClinicalSummaryController {

    private final ClinicalSummaryService service;

    public ClinicalSummaryController(ClinicalSummaryService service) {
        this.service = service;
    }

    /** A patient's clinical summaries, newest first (narrative consent-masked per caller). */
    @GetMapping
    public List<ClinicalSummaryDto> list(@PathVariable UUID patientId) {
        return service.list(patientId);
    }

    /** One clinical summary (404 if it is not this patient's or not in the tenant). */
    @GetMapping("/{summaryId}")
    public ClinicalSummaryDto getOne(@PathVariable UUID patientId, @PathVariable UUID summaryId) {
        return service.getById(patientId, summaryId);
    }

    /** Record a clinical summary for the patient (write role + active assignment; unknown code → 400). */
    @PostMapping
    public ResponseEntity<ClinicalSummaryDto> create(
            @PathVariable UUID patientId, @Valid @RequestBody ClinicalSummaryCreateRequest request) {
        ClinicalSummaryDto created = service.create(patientId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/clinical-summaries/" + created.id()))
                .body(created);
    }
}
