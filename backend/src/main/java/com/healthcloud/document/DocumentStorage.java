package com.healthcloud.document;

import java.util.UUID;

/**
 * The blob store for document bytes (source-of-truth §19: bytes in a private object store, metadata in
 * PostgreSQL). This is the seam that keeps the rest of the app storage-agnostic: a local-filesystem stand-in
 * backs it now, and private S3 plugs in at the cloud phase without touching the service or controller.
 *
 * <p>Implementations own the key layout: {@link #store} generates and returns an opaque {@code storageKey}
 * that {@link #load}/{@link #delete} later resolve. The key is persisted as the document's {@code storageKey}.
 */
public interface DocumentStorage {

    /**
     * Persist the given bytes for a patient's document and return the opaque storage key that addresses them.
     * The organization and patient scope the key so the layout mirrors the tenancy model.
     */
    String store(UUID organizationId, UUID patientId, byte[] content);

    /** Read back the bytes previously stored under {@code storageKey}. */
    byte[] load(String storageKey);

    /** Delete the bytes stored under {@code storageKey} (no-op if already gone). */
    void delete(String storageKey);
}
