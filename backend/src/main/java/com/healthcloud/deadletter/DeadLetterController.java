package com.healthcloud.deadletter;

import com.healthcloud.common.PageRequests;
import com.healthcloud.common.PageResponse;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dead-letter inspection + replay API (source-of-truth §Phase 8 slices 5–6). Thin controller (§31.5): the
 * ORG_ADMIN role gate, tenant scoping, and the replay orchestration live in the services.
 */
@RestController
@RequestMapping("/api/v1/dead-letter-events")
public class DeadLetterController {

    /** Fields a caller may sort the dead-letter queue by (allowlisted — an unknown field is a clean 400). */
    private static final Set<String> SORTABLE_FIELDS = Set.of("createdAt", "sourceTopic");

    /** Default ordering when the caller supplies no {@code sort}: newest first (the queue's prior behavior). */
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final DeadLetterService service;
    private final DeadLetterReplayService replayService;

    public DeadLetterController(DeadLetterService service, DeadLetterReplayService replayService) {
        this.service = service;
        this.replayService = replayService;
    }

    /**
     * A page of the caller's tenant's dead-letter events (§Phase 9, ORG_ADMIN). Paging/sorting come from
     * {@code page}/{@code size}/{@code sort}; an unknown sort field is a 400. Returns a {@link PageResponse}.
     */
    @GetMapping
    public PageResponse<DeadLetterEventDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        Pageable pageable = PageRequests.toPageable(page, size, sort, SORTABLE_FIELDS, DEFAULT_SORT);
        return service.listForTenant(q, pageable);
    }

    /** Re-drive a dead-letter record onto its source topic (ORG_ADMIN); marks it replayed and audits the action. */
    @PostMapping("/{id}/replay")
    public DeadLetterEventDto replay(@PathVariable UUID id) {
        return replayService.replay(id);
    }
}
