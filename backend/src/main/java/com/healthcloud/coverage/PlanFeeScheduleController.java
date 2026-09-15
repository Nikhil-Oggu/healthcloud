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
 * Plan fee-schedule API, nested under the coverage plan it configures (source-of-truth §Phase 5). Thin controller
 * (§31.5): tenant scoping, authorization and code validation live in {@link PlanFeeScheduleService}. Reads are
 * open to any same-tenant user; add/remove require ORG_ADMIN. A plan in another tenant is a secure 404.
 */
@RestController
@RequestMapping("/api/v1/coverage-plans/{planId}/fee-schedule")
public class PlanFeeScheduleController {

    private final PlanFeeScheduleService service;

    public PlanFeeScheduleController(PlanFeeScheduleService service) {
        this.service = service;
    }

    /** A plan's fee-schedule entries (priced procedures). */
    @GetMapping
    public List<PlanFeeScheduleDto> list(@PathVariable UUID planId) {
        return service.list(planId);
    }

    /** Price a procedure on the plan (ORG_ADMIN). */
    @PostMapping
    public ResponseEntity<PlanFeeScheduleDto> add(
            @PathVariable UUID planId, @Valid @RequestBody AddFeeScheduleRequest request) {
        PlanFeeScheduleDto created = service.add(planId, request);
        return ResponseEntity
                .created(URI.create("/api/v1/coverage-plans/" + planId + "/fee-schedule/" + created.id()))
                .body(created);
    }

    /** Remove a fee-schedule entry from the plan (ORG_ADMIN). */
    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> remove(@PathVariable UUID planId, @PathVariable UUID entryId) {
        service.remove(planId, entryId);
        return ResponseEntity.noContent().build();
    }
}
