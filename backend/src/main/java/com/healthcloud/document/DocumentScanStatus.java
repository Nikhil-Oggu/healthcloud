package com.healthcloud.document;

/**
 * The malware-scan lifecycle of a stored document (source-of-truth §19). A freshly uploaded document is
 * {@code PENDING} until a scanner has examined it; it then becomes {@code CLEAN} (safe to download) or
 * {@code QUARANTINED} (withheld). The scanner and the download quarantine gate arrive in the next slice; this
 * slice records {@code CLEAN} on upload so the shape is in place and that work is purely additive.
 */
public enum DocumentScanStatus {
    PENDING,
    CLEAN,
    QUARANTINED
}
