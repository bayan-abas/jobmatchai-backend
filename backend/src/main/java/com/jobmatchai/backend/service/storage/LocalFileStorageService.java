package com.jobmatchai.backend.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

// Default implementation (app.storage.type unset, or explicitly "local") - the exact same
// disk-based logic CVController/UserDeletionService always used inline, centralized here so both
// classes (and any future caller) share one implementation instead of two independently
// hand-maintained copies of the same path-construction/read/write/delete code.
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    @Value("${app.upload.dir:uploads/cvs/}")
    private String uploadDir;

    private Path uploadPath() {
        return Paths.get(System.getProperty("user.dir")).resolve(uploadDir).normalize().toAbsolutePath();
    }

    // Same guard CVController#deleteCV/serveCvFile already applied inline before this class
    // existed - resolving `key` against the upload directory and confirming the result is still
    // WITHIN it blocks a path-traversal key (e.g. "../../etc/passwd") from ever escaping
    // app.upload.dir. Applied uniformly to every operation here now, including the two (extract/
    // analyze) that previously skipped this check - harmless, since `key` only ever comes from a
    // server-generated UUID stored in the DB on those paths, never raw user input.
    private Path resolve(String key) {
        Path resolved = uploadPath().resolve(key).normalize().toAbsolutePath();
        if (!resolved.startsWith(uploadPath())) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return resolved;
    }

    @Override
    public void store(File source, String key) throws IOException {
        Path destination = resolve(key);
        Files.createDirectories(destination.getParent());
        Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            // See FileStorageService#delete's own comment - best-effort, never fails the caller.
        }
    }

    @Override
    public Resource loadAsResource(String key) throws IOException {
        return new UrlResource(resolve(key).toUri());
    }

    @Override
    public <T> T withLocalFile(String key, FileFunction<T> action) throws IOException {
        // The stored object already IS a real local file - nothing to download/copy/clean up.
        return action.apply(resolve(key).toFile());
    }
}
