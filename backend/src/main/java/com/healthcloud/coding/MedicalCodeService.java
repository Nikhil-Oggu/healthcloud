package com.healthcloud.coding;

import com.healthcloud.context.UserContextAccessor;
import com.healthcloud.error.NotFoundException;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read access to the medical code catalog (source-of-truth §Phase 4). Codes are global reference data, so —
 * unlike the tenant-owned services — there is no tenant scoping, relationship gate, or consent here: the only
 * requirement is an authenticated caller (any role). Search results are bounded so a broad query can't return
 * the whole catalog.
 */
@Service
@Transactional(readOnly = true)
public class MedicalCodeService {

    /** Upper bound on how many codes a single search returns. */
    static final int MAX_RESULTS = 50;

    private final MedicalCodeRepository codes;
    private final UserContextAccessor userContext;

    public MedicalCodeService(MedicalCodeRepository codes, UserContextAccessor userContext) {
        this.codes = codes;
        this.userContext = userContext;
    }

    /**
     * Search the catalog by an optional system and optional free-text term (a code prefix or a description
     * substring). Returns up to {@link #MAX_RESULTS} active codes. Requires an authenticated caller.
     */
    public List<MedicalCodeDto> search(CodeSystem system, String query) {
        userContext.requireUser();
        String term = normalize(query);
        return codes.search(system, term, PageRequest.of(0, MAX_RESULTS))
                .stream().map(MedicalCodeDto::from).toList();
    }

    /**
     * One active code by system + code (case-insensitive). Requires an authenticated caller; an unknown code
     * is a 404. (An unknown {@code system} is rejected as a 400 before reaching here — the enum bind fails.)
     */
    public MedicalCodeDto getOne(CodeSystem system, String code) {
        userContext.requireUser();
        return codes.findByCodeSystemAndCodeIgnoreCaseAndActiveTrue(system, code)
                .map(MedicalCodeDto::from)
                .orElseThrow(NotFoundException::new);
    }

    /** Trim, and treat a blank term as "no filter" (null) so it matches everything. */
    private static String normalize(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
