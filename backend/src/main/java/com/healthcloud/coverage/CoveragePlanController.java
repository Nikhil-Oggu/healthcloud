package com.healthcloud.coverage;

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
 * Coverage plan API. Thin controller (§31.5): tenant scoping, authorization and the create transaction live in
 * {@link CoveragePlanService}. Every route is authenticated and tenant-scoped (another tenant's plan is a
 * secure 404). Reads are open to any same-tenant user; creation requires ORG_ADMIN.
 */
@RestController
@RequestMapping("/api/v1/coverage-plans")
public class CoveragePlanController {

    private final CoveragePlanService service;

    public CoveragePlanController(CoveragePlanService service) {
        this.service = service;
    }

    /** All coverage plans in the caller's tenant. */
    @GetMapping
    public List<CoveragePlanDto> list() {
        return service.list();
    }

    /** One coverage plan by id, scoped to the caller's tenant (404 across tenants). */
    @GetMapping("/{id}")
    public CoveragePlanDto getOne(@PathVariable UUID id) {
        return service.getById(id);
    }

    /** Create a coverage plan (ORG_ADMIN only). */
    @PostMapping
    public ResponseEntity<CoveragePlanDto> create(@Valid @RequestBody CreateCoveragePlanRequest request) {
        CoveragePlanDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/coverage-plans/" + created.id())).body(created);
    }
}
