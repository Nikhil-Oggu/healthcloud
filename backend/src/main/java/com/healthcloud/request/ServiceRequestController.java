package com.healthcloud.request;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
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
 * Service-request API. Thin controller (§31.5): tenant scoping, authorization, and the create
 * transaction live in {@link ServiceRequestService}. Every route is authenticated and tenant-scoped.
 */
@RestController
@RequestMapping("/api/v1/requests")
public class ServiceRequestController {

    private final ServiceRequestService service;

    public ServiceRequestController(ServiceRequestService service) {
        this.service = service;
    }

    /** All requests in the caller's organization, optionally filtered to one patient. */
    @GetMapping
    public List<ServiceRequestDto> list(@RequestParam(required = false) UUID patientId) {
        return service.list(Optional.ofNullable(patientId));
    }

    /** One request by id, scoped to the caller's organization (404 across tenants). */
    @GetMapping("/{id}")
    public ServiceRequestDto getOne(@PathVariable UUID id) {
        return service.getById(id);
    }

    /** Create a DRAFT request for a patient in the caller's organization. */
    @PostMapping
    public ResponseEntity<ServiceRequestDto> create(@Valid @RequestBody ServiceRequestCreateRequest request) {
        ServiceRequestDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/requests/" + created.id())).body(created);
    }
}
