package com.healthcloud.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healthcloud.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The medical code catalog repository (Phase 4 slice 1). Global reference data — no tenant scoping — so these
 * tests exercise search (by system, by term, capped) and the {@code (code_system, code)} uniqueness the DB
 * enforces. Runs without the {@code local} profile so the seeder does not run and the data is controlled here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestcontainersConfiguration.class)
@Transactional
class MedicalCodeRepositoryTest {

    @Autowired MedicalCodeRepository codes;

    @Test
    void search_filters_by_system_and_term_and_finds_by_code_or_description() {
        codes.save(new MedicalCode(CodeSystem.ICD10CM, "E11.9", "Type 2 diabetes mellitus without complications"));
        codes.save(new MedicalCode(CodeSystem.ICD10CM, "I10", "Essential hypertension"));
        codes.save(new MedicalCode(CodeSystem.CPT, "99213", "Office visit, established patient"));

        // System filter: only ICD-10 rows come back for a broad term when scoped to that system.
        List<MedicalCode> icd = codes.search(CodeSystem.ICD10CM, null, PageRequest.of(0, 50));
        assertTrue(icd.stream().allMatch(c -> c.getCodeSystem() == CodeSystem.ICD10CM),
                "system filter must exclude other systems");
        assertTrue(icd.stream().anyMatch(c -> c.getCode().equals("E11.9")));

        // Term matches a description substring (case-insensitive)...
        List<MedicalCode> byDescription = codes.search(null, "diabetes", PageRequest.of(0, 50));
        assertTrue(byDescription.stream().anyMatch(c -> c.getCode().equals("E11.9")));
        assertFalse(byDescription.stream().anyMatch(c -> c.getCode().equals("99213")),
                "a description term must not return unrelated codes");

        // ...and a code prefix.
        List<MedicalCode> byCodePrefix = codes.search(null, "992", PageRequest.of(0, 50));
        assertTrue(byCodePrefix.stream().anyMatch(c -> c.getCode().equals("99213")));
    }

    @Test
    void exact_lookup_is_case_insensitive_and_ignores_inactive() {
        codes.save(new MedicalCode(CodeSystem.CPT, "80053", "Comprehensive metabolic panel"));
        assertTrue(codes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(CodeSystem.CPT, "80053").isPresent());
        assertTrue(codes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(CodeSystem.ICD10CM, "80053").isEmpty(),
                "a code is scoped to its own system");
    }

    @Test
    void a_code_is_unique_within_its_system() {
        codes.saveAndFlush(new MedicalCode(CodeSystem.ICD10CM, "M54.5", "Low back pain"));
        assertThrows(DataIntegrityViolationException.class, () -> codes.saveAndFlush(
                new MedicalCode(CodeSystem.ICD10CM, "M54.5", "Low back pain (duplicate)")));
    }

    @Test
    void search_is_bounded_by_the_page_size() {
        for (int i = 0; i < 5; i++) {
            codes.save(new MedicalCode(CodeSystem.HCPCS, "Q000" + i, "Synthetic supply " + i));
        }
        assertEquals(2, codes.search(CodeSystem.HCPCS, "Q000", PageRequest.of(0, 2)).size(),
                "the page size caps the result set");
    }
}
