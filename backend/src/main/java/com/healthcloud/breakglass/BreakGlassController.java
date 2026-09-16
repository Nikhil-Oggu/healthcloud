package com.healthcloud.breakglass;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The break-glass emergency-access API (source-of-truth §Phase 7). Thin controller (§31.5): the PROVIDER role
 * gate, patient/tenant validation, the grant + audit-event write all live in {@link BreakGlassService}. Every
 * route is authenticated and tenant-scoped.
 */
@RestController
@RequestMapping("/api/v1/break-glass")
public class BreakGlassController {

    private final BreakGlassService service;

    public BreakGlassController(BreakGlassService service) {
        this.service = service;
    }

    /** Break the glass for a patient (PROVIDER) — a time-boxed, audited emergency grant. */
    @PostMapping
    public ResponseEntity<BreakGlassGrantDto> create(@Valid @RequestBody CreateBreakGlassRequest request) {
        BreakGlassGrantDto created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/break-glass/" + created.id())).body(created);
    }

    /** The caller's own live break-glass grants (newest first). */
    @GetMapping
    public List<BreakGlassGrantDto> listMine() {
        return service.listMine();
    }
}
