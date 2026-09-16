package com.healthcloud.identity;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider directory API (§Phase 6 provider network). Thin controller (§31.5): tenant scoping and the role gate
 * live in {@link ProviderDirectoryService}. Read-only — it fills the rendering-provider picker on claim create.
 */
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderController {

    private final ProviderDirectoryService service;

    public ProviderController(ProviderDirectoryService service) {
        this.service = service;
    }

    /** The caller's tenant's active PROVIDERs (claim-create roles). */
    @GetMapping
    public List<ProviderDto> list() {
        return service.list();
    }
}
