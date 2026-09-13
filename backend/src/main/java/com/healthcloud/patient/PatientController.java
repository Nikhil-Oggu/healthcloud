package com.healthcloud.patient;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient read API. Thin controller (source-of-truth §31.5): all tenant scoping and business rules
 * live in {@link PatientService}. Every route is authenticated (SecurityConfig) and tenant-scoped.
 */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    /** All patients in the caller's organization. */
    @GetMapping
    public List<PatientDto> list() {
        return patientService.listForCurrentTenant();
    }

    /** One patient by id, scoped to the caller's organization (404 across tenants). */
    @GetMapping("/{id}")
    public PatientDto getOne(@PathVariable UUID id) {
        return patientService.getById(id);
    }

    /** Create a patient in the caller's organization (coordinator/admin only). */
    @PostMapping
    public ResponseEntity<PatientDto> create(@Valid @RequestBody PatientCreateRequest request) {
        PatientDto created = patientService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/patients/" + created.id())).body(created);
    }

    /** Update a patient's mutable fields (coordinator/admin only; optimistic-locked). */
    @PatchMapping("/{id}")
    public PatientDto update(@PathVariable UUID id, @Valid @RequestBody PatientUpdateRequest request) {
        return patientService.update(id, request);
    }
}
