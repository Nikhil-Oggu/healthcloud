package com.healthcloud.coverage;

import com.healthcloud.relationship.AssignmentCandidateDto;
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
 * Plan network-provider API, nested under the coverage plan it configures (source-of-truth §Phase 6, provider
 * network). Thin controller (§31.5): tenant scoping, authorization and provider validation live in
 * {@link PlanNetworkProviderService}. Reads are open to any same-tenant user; add/remove and the candidate
 * picker require ORG_ADMIN. A plan in another tenant is a secure 404. Mirrors
 * {@link PlanPriorAuthRequirementController}.
 */
@RestController
@RequestMapping("/api/v1/coverage-plans/{planId}/network-providers")
public class PlanNetworkProviderController {

    private final PlanNetworkProviderService service;

    public PlanNetworkProviderController(PlanNetworkProviderService service) {
        this.service = service;
    }

    /** The plan's network providers (sorted by name). */
    @GetMapping
    public List<PlanNetworkProviderDto> list(@PathVariable UUID planId) {
        return service.list(planId);
    }

    /** Same-tenant PROVIDERs not already in the plan's network — the picker source (ORG_ADMIN). */
    @GetMapping("/candidates")
    public List<AssignmentCandidateDto> candidates(@PathVariable UUID planId) {
        return service.listCandidates(planId);
    }

    /** Add a provider to the plan's network (ORG_ADMIN). */
    @PostMapping
    public ResponseEntity<PlanNetworkProviderDto> add(
            @PathVariable UUID planId, @Valid @RequestBody AddNetworkProviderRequest request) {
        PlanNetworkProviderDto created = service.add(planId, request);
        return ResponseEntity
                .created(URI.create(
                        "/api/v1/coverage-plans/" + planId + "/network-providers/" + created.id()))
                .body(created);
    }

    /** Remove a provider from the plan's network (ORG_ADMIN). */
    @DeleteMapping("/{networkProviderId}")
    public ResponseEntity<Void> remove(@PathVariable UUID planId, @PathVariable UUID networkProviderId) {
        service.remove(planId, networkProviderId);
        return ResponseEntity.noContent().build();
    }
}
