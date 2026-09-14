package com.healthcloud.document;

/**
 * Malware scanner for uploaded document bytes (source-of-truth §19). Returns the verdict for a file:
 * {@link DocumentScanStatus#CLEAN} (safe) or {@link DocumentScanStatus#QUARANTINED} (flagged, withheld).
 *
 * <p>The scan runs synchronously at upload time in this phase, behind this interface so the real
 * (asynchronous, event-driven) scanning worker can replace it at Phase 8 with no change to the API shape —
 * a document is written {@link DocumentScanStatus#PENDING} and the worker later flips it to CLEAN/QUARANTINED,
 * and the download gate already refuses anything that is not CLEAN.
 */
public interface DocumentScanner {

    /** Scan the given bytes and return the verdict (never {@code PENDING}). */
    DocumentScanStatus scan(byte[] content, String fileName);
}
