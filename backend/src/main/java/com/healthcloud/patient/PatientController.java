package com.healthcloud.patient;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
