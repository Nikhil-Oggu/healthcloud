package com.healthcloud.deadletter;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dead-letter inspection + replay API (source-of-truth §Phase 8 slices 5–6). Thin controller (§31.5): the
 * ORG_ADMIN role gate, tenant scoping, and the replay orchestration live in the services.
 */
@RestController
@RequestMapping("/api/v1/dead-letter-events")
public class DeadLetterController {

    private final DeadLetterService service;
    private final DeadLetterReplayService replayService;

    public DeadLetterController(DeadLetterService service, DeadLetterReplayService replayService) {
        this.service = service;
        this.replayService = replayService;
    }

    /** The caller's tenant's dead-letter events, newest first (ORG_ADMIN). */
    @GetMapping
    public List<DeadLetterEventDto> list() {
        return service.listForTenant();
    }

    /** Re-drive a dead-letter record onto its source topic (ORG_ADMIN); marks it replayed and audits the action. */
    @PostMapping("/{id}/replay")
    public DeadLetterEventDto replay(@PathVariable UUID id) {
        return replayService.replay(id);
    }
}
