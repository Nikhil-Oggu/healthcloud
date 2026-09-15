package com.healthcloud.coding;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Medical code catalog API. Thin controller (source-of-truth §31.5): the lookup logic lives in
 * {@link MedicalCodeService}. Every route requires an authenticated caller (SecurityConfig); the catalog is
 * global reference data, so it is not tenant-scoped. An unknown {@code system} value binds to no enum constant
 * and is rejected as a 400 by the global handler.
 */
@RestController
@RequestMapping("/api/v1/medical-codes")
public class MedicalCodeController {

    private final MedicalCodeService medicalCodeService;

    public MedicalCodeController(MedicalCodeService medicalCodeService) {
        this.medicalCodeService = medicalCodeService;
    }

    /** Search codes by an optional {@code system} and optional free-text {@code q} (capped result set). */
    @GetMapping
    public List<MedicalCodeDto> search(
            @RequestParam(required = false) CodeSystem system,
            @RequestParam(required = false) String q) {
        return medicalCodeService.search(system, q);
    }

    /** One code by system + code (404 if not found, 400 if the system is unknown). */
    @GetMapping("/{system}/{code}")
    public MedicalCodeDto getOne(@PathVariable CodeSystem system, @PathVariable String code) {
        return medicalCodeService.getOne(system, code);
    }
}
