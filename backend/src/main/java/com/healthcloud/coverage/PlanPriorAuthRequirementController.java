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
 * Plan prior-auth-requirement API, nested under the coverage plan it configures (source-of-truth §Phase 6). Thin
 * controller (§31.5): tenant scoping, authorization and code validation live in
 * {@link PlanPriorAuthRequirementService}. Reads are open to any same-tenant user; add/remove require ORG_ADMIN.
 * A plan in another tenant is a secure 404. Mirrors {@link PlanExclusionController}.
 */
@RestController
@RequestMapping("/api/v1/coverage-plans/{planId}/prior-auth-requirements")
public class PlanPriorAuthRequirementController {

    private final PlanPriorAuthRequirementService service;

    public PlanPriorAuthRequirementController(PlanPriorAuthRequirementService service) {
        this.service = service;
    }

    /** The plan's procedures that require prior authorization. */
    @GetMapping
    public List<PlanPriorAuthRequirementDto> list(@PathVariable UUID planId) {
        return service.list(planId);
    }

    /** Mark a procedure as requiring prior authorization under the plan (ORG_ADMIN). */
    @PostMapping
    public ResponseEntity<PlanPriorAuthRequirementDto> add(
            @PathVariable UUID planId, @Valid @RequestBody AddPriorAuthRequirementRequest request) {
        PlanPriorAuthRequirementDto created = service.add(planId, request);
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/coverage-plans/" + planId + "/prior-auth-requirements/" + created.id()))
                .body(created);
    }

    /** Remove a prior-auth requirement from the plan (ORG_ADMIN). */
    @DeleteMapping("/{requirementId}")
    public ResponseEntity<Void> remove(@PathVariable UUID planId, @PathVariable UUID requirementId) {
        service.remove(planId, requirementId);
        return ResponseEntity.noContent().build();
    }
}
