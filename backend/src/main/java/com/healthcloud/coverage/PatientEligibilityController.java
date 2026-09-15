package com.healthcloud.coverage;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient eligibility API, nested under the patient it concerns (source-of-truth §Phase 4). Thin controller
 * (§31.5): tenant scoping, the object/relationship gate, plan validation and the non-overlap check live in
 * {@link PatientEligibilityService}. Every route is authenticated and tenant-scoped (a patient in another
 * tenant is a secure 404).
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/eligibility")
public class PatientEligibilityController {

    private final PatientEligibilityService service;

    public PatientEligibilityController(PatientEligibilityService service) {
        this.service = service;
    }

    /** A patient's eligibility records; with {@code asOf}, only the period covering that date. */
    @GetMapping
    public List<PatientEligibilityDto> list(
            @PathVariable UUID patientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return service.list(patientId, Optional.ofNullable(asOf));
    }

    /** One eligibility record (404 if it is not this patient's or not in the tenant). */
    @GetMapping("/{eligibilityId}")
    public PatientEligibilityDto getOne(@PathVariable UUID patientId, @PathVariable UUID eligibilityId) {
        return service.getById(patientId, eligibilityId);
    }

    /** Enroll a patient in a coverage plan for a period (CARE_COORDINATOR/ORG_ADMIN). */
    @PostMapping
    public ResponseEntity<PatientEligibilityDto> enroll(
            @PathVariable UUID patientId, @Valid @RequestBody EnrollEligibilityRequest request) {
        PatientEligibilityDto created = service.enroll(patientId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/patients/" + patientId + "/eligibility/" + created.id()))
                .body(created);
    }
}
