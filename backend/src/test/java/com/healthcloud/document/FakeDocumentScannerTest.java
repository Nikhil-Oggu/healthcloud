package com.healthcloud.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The synthetic malware scanner (§19) flags the EICAR test signature and passes everything else. The signature
 * is reconstructed from fragments here too, so the contiguous string never appears as a literal in the test.
 */
class FakeDocumentScannerTest {

    private final FakeDocumentScanner scanner = new FakeDocumentScanner();

    /** The EICAR anti-malware test signature (harmless), assembled from parts. */
    private static final String EICAR =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}" + "$EICAR-STANDARD-ANTIVIRUS-" + "TEST-FILE!$H+H*";

    @Test
    void an_ordinary_file_is_clean() {
        byte[] content = "a perfectly ordinary synthetic care note".getBytes(StandardCharsets.UTF_8);
        assertEquals(DocumentScanStatus.CLEAN, scanner.scan(content, "note.txt"));
    }

    @Test
    void the_eicar_signature_is_quarantined() {
        byte[] content = EICAR.getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(DocumentScanStatus.QUARANTINED, scanner.scan(content, "eicar.txt"));
    }

    @Test
    void the_eicar_signature_embedded_in_a_larger_file_is_quarantined() {
        byte[] content = ("harmless preamble\n" + EICAR + "\nharmless trailer")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(DocumentScanStatus.QUARANTINED, scanner.scan(content, "sneaky.txt"));
    }

    @Test
    void an_empty_file_is_clean() {
        assertEquals(DocumentScanStatus.CLEAN, scanner.scan(new byte[0], "empty.txt"));
    }
}
