package com.healthcloud.deadletter;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dead-letter inspection API (source-of-truth §Phase 8 slice 5). Thin controller (§31.5): the ORG_ADMIN role
 * gate and tenant scoping live in {@link DeadLetterService}. Replay is added in a later slice.
 */
@RestController
@RequestMapping("/api/v1/dead-letter-events")
public class DeadLetterController {

    private final DeadLetterService service;

    public DeadLetterController(DeadLetterService service) {
        this.service = service;
    }

    /** The caller's tenant's dead-letter events, newest first (ORG_ADMIN). */
    @GetMapping
    public List<DeadLetterEventDto> list() {
        return service.listForTenant();
    }
}
