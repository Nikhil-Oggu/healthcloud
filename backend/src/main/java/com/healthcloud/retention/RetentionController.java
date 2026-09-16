package com.healthcloud.retention;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data-retention API (source-of-truth §Phase 7). Thin controller (§31.5): the ORG_ADMIN role gate, tenant
 * scoping, the purge and its audit event all live in {@link RetentionService}. A POST because it changes state,
 * so it takes the CSRF handshake like every other mutation.
 */
@RestController
@RequestMapping("/api/v1/retention")
public class RetentionController {

    private final RetentionService service;

    public RetentionController(RetentionService service) {
        this.service = service;
    }

    /** Run the break-glass retention purge for the caller's tenant (ORG_ADMIN). */
    @PostMapping("/break-glass/run")
    public RetentionPurgeResultDto runBreakGlassPurge() {
        return service.runBreakGlassPurge();
    }
}
