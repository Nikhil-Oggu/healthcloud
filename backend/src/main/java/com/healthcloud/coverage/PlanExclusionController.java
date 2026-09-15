package com.healthcloud.coverage;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plan exclusion API, nested under the coverage plan it configures (source-of-truth §Phase 5). Thin controller
 * (§31.5): tenant scoping, authorization and code validation live in {@link PlanExclusionService}. Reads are
 * open to any same-tenant user; add/remove require ORG_ADMIN. A plan in another tenant is a secure 404.
 */
@RestController
@RequestMapping("/api/v1/coverage-plans/{planId}/exclusions")
public class PlanExclusionController {

    private final PlanExclusionService service;

    public PlanExclusionController(PlanExclusionService service) {
        this.service = service;
    }

    /** A plan's excluded procedures. */
    @GetMapping
    public List<PlanExclusionDto> list(@PathVariable UUID planId) {
        return service.list(planId);
    }

    /** Exclude a procedure from the plan (ORG_ADMIN). */
    @PostMapping
    public ResponseEntity<PlanExclusionDto> add(
            @PathVariable UUID planId, @Valid @RequestBody AddPlanExclusionRequest request) {
        PlanExclusionDto created = service.add(planId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/coverage-plans/" + planId + "/exclusions/" + created.id()))
                .body(created);
    }

    /** Remove an exclusion from the plan (ORG_ADMIN). */
    @DeleteMapping("/{exclusionId}")
    public ResponseEntity<Void> remove(@PathVariable UUID planId, @PathVariable UUID exclusionId) {
        service.remove(planId, exclusionId);
        return ResponseEntity.noContent().build();
    }
}
