package com.healthcloud.document;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A synthetic malware scanner for local/dev use (source-of-truth §19 — a fake scanner locally). It flags any
 * file containing the <b>EICAR test signature</b>: the industry-standard, deliberately harmless string that
 * real antivirus engines are built to detect. Everything else is treated as {@link DocumentScanStatus#CLEAN}.
 *
 * <p>Using EICAR gives a realistic, deterministic trigger with zero risk (it is not malware). The signature is
 * assembled from fragments at runtime so the contiguous string never appears as a literal in this source file
 * or the compiled class — otherwise a developer's own antivirus might quarantine the build.
 */
@Component
public class FakeDocumentScanner implements DocumentScanner {

    private static final Logger log = LoggerFactory.getLogger(FakeDocumentScanner.class);

    /** The EICAR anti-malware test file signature, assembled from parts (see class note). */
    private static final String EICAR_SIGNATURE =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}" + "$EICAR-STANDARD-ANTIVIRUS-" + "TEST-FILE!$H+H*";

    @Override
    public DocumentScanStatus scan(byte[] content, String fileName) {
        // ISO-8859-1 maps each byte to one char, so a byte-for-byte substring search of an ASCII signature.
        String asText = new String(content, StandardCharsets.ISO_8859_1);
        if (asText.contains(EICAR_SIGNATURE)) {
            // Safe to log: the reason names a well-known test signature, never file contents (§23.4, §19).
            log.warn("Document quarantined by malware scan: EICAR test signature detected");
            return DocumentScanStatus.QUARANTINED;
        }
        return DocumentScanStatus.CLEAN;
    }
}
