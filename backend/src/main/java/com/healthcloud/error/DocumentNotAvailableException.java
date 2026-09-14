package com.healthcloud.error;

/**
 * A document exists and the caller may reach it, but it cannot be downloaded in its current scan state —
 * it was quarantined by the malware scan, or has not yet been scanned (source-of-truth §19). Maps to HTTP
 * 409 with the stable {@link ErrorCode#DOCUMENT_NOT_AVAILABLE} code so the client can tell this apart from a
 * missing resource or a plain conflict. This is deliberately NOT a secure 404: an authorized caller already
 * sees the document (with its scan status) in the listing, so hiding it here would confirm nothing new.
 */
public class DocumentNotAvailableException extends ApiException {

    public DocumentNotAvailableException(String message) {
        super(ErrorCode.DOCUMENT_NOT_AVAILABLE, message);
    }
}
