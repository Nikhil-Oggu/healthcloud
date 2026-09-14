package com.healthcloud.document;

/**
 * An unexpected failure reading or writing document bytes in the {@link DocumentStorage} (an I/O error, a
 * missing blob). This is a server-side fault, not a client error: it maps to a generic 500 via the global
 * handler. The message is for server logs — never surface storage internals to the client.
 */
public class DocumentStorageException extends RuntimeException {

    public DocumentStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public DocumentStorageException(String message) {
        super(message);
    }
}
