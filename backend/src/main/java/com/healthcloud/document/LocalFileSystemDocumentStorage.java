package com.healthcloud.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local-filesystem {@link DocumentStorage} — the development/test stand-in for private S3 (source-of-truth
 * §19). Bytes are written under a configured base directory ({@code healthcloud.documents.dir}); the layout is
 * {@code <organizationId>/<patientId>/<random-uuid>}, mirroring the tenancy model. The returned storage key is
 * that relative path and is persisted with the document metadata.
 *
 * <p>Keys are generated here (random UUIDs), so they never contain caller-supplied text; even so, every
 * resolved path is checked to stay under the base directory (defense in depth against traversal).
 */
@Component
public class LocalFileSystemDocumentStorage implements DocumentStorage {

    private final Path baseDir;

    public LocalFileSystemDocumentStorage(@Value("${healthcloud.documents.dir}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID organizationId, UUID patientId, byte[] content) {
        String storageKey = organizationId + "/" + patientId + "/" + UUID.randomUUID();
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store document bytes", e);
        }
        return storageKey;
    }

    @Override
    public byte[] load(String storageKey) {
        Path source = resolve(storageKey);
        try {
            return Files.readAllBytes(source);
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read document bytes", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to delete document bytes", e);
        }
    }

    /** Resolve a storage key under the base directory, refusing anything that escapes it. */
    private Path resolve(String storageKey) {
        Path resolved = baseDir.resolve(storageKey).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new DocumentStorageException("Resolved document path escapes the storage root");
        }
        return resolved;
    }
}
