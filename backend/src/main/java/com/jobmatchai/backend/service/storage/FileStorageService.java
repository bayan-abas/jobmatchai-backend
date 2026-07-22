package com.jobmatchai.backend.service.storage;

import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;

// Abstraction over WHERE a candidate's CV file physically lives - local disk in dev
// (LocalFileStorageService, the default), Amazon S3 in production (S3FileStorageService,
// selected via app.storage.type=s3 - see application-production.properties). Every method here
// mirrors an operation CVController/UserDeletionService already performed ad hoc, directly
// against java.nio.file/java.io, before this abstraction existed - a drop-in replacement for
// that inline logic, not a new feature: same keys (the existing UUID.<ext> convention, generated
// by the caller, never by this interface), same authorization model (this layer does zero auth -
// every caller already checks ownership before reaching here), same validation rules (still
// enforced entirely in CVController before store() is ever called).
public interface FileStorageService {

    // Persists the bytes of `source` under `key`, overwriting any existing object at that key.
    void store(File source, String key) throws IOException;

    boolean exists(String key);

    // Deletes the object at `key`. A no-op (not an error) if it doesn't exist, or if the
    // underlying delete itself fails - matches the pre-abstraction behavior, where a CV file
    // failing to delete was never surfaced as a request failure either; it's an orphaned-file
    // cleanup concern, not a correctness one.
    void delete(String key);

    // A Resource suitable for streaming directly back in an HTTP response body - backs the
    // candidate/company CV download endpoints. Callers must check exists() first; this doesn't
    // re-check existence itself (UrlResource/InputStreamResource's own exists() semantics differ
    // enough between local and S3 that relying on either post-hoc would be inconsistent).
    Resource loadAsResource(String key) throws IOException;

    // Runs `action` against a real java.io.File containing the object stored under `key`. Exists
    // solely because CVTextExtractorService's Tika-based extraction needs a java.io.File API, not
    // a stream - CVTextExtractorService itself is never touched by this abstraction.
    // Implementations decide internally whether that means handing back the real stored file
    // (local) or a short-lived temp copy cleaned up automatically once `action` returns (S3) -
    // callers never need to know which, and never manage cleanup themselves.
    <T> T withLocalFile(String key, FileFunction<T> action) throws IOException;

    @FunctionalInterface
    interface FileFunction<T> {
        T apply(File file) throws IOException;
    }
}
