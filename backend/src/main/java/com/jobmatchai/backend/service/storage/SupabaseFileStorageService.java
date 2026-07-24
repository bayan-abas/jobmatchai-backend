package com.jobmatchai.backend.service.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// Selected when app.storage.type=supabase (see application-production.properties). Talks to the
// Supabase Storage HTTP REST API directly (PUT/GET/HEAD/DELETE under /storage/v1/object/{bucket}/
// {key}) rather than the S3-compatible endpoint, specifically so the app never needs AWS-style
// access-key/secret credentials - a single service_role key (already required for every other
// Supabase interaction this app has) both authenticates the request and bypasses row-level
// security, since this layer does its own ownership checks upstream (see FileStorageService's own
// comment). Same object keys (the existing UUID.<ext> convention) and same store/exists/delete/
// loadAsResource/withLocalFile contract as LocalFileStorageService and the S3 implementation it
// replaces - callers never know the storage backend changed.
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "supabase")
public class SupabaseFileStorageService implements FileStorageService {

    @Value("${app.supabase.url}")
    private String supabaseUrl;

    @Value("${app.supabase.service-role-key}")
    private String serviceRoleKey;

    @Value("${app.supabase.storage-bucket}")
    private String bucket;

    private RestClient client;

    // Built once at startup (not lazily on first use) specifically so the singleton bean never
    // races multiple request threads over initializing the same field - matches the prior S3
    // client's own init pattern.
    @PostConstruct
    private void init() {
        client = RestClient.builder()
                .baseUrl(supabaseUrl + "/storage/v1/object")
                // Both headers are the standard Supabase idiom: apikey identifies the project/key,
                // Authorization carries the same key as a bearer token for RLS evaluation. Using
                // the service_role key for both bypasses RLS entirely - this layer already enforces
                // ownership before ever calling into storage (see FileStorageService's own comment).
                .defaultHeader("apikey", serviceRoleKey)
                .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                .build();
    }

    @Override
    public void store(File source, String key) throws IOException {
        byte[] bytes = Files.readAllBytes(source.toPath());
        // PUT (not POST) is Supabase Storage's upsert route - it overwrites an existing object at
        // the same key instead of failing, matching this method's own "overwriting any existing
        // object" contract without needing a separate x-upsert header.
        client.put()
                .uri("/{bucket}/{key}", bucket, key)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public boolean exists(String key) {
        try {
            client.head().uri("/{bucket}/{key}", bucket, key).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.delete().uri("/{bucket}/{key}", bucket, key).retrieve().toBodilessEntity();
        } catch (Exception e) {
            // See FileStorageService#delete's own comment - best-effort, never fails the caller.
        }
    }

    @Override
    public Resource loadAsResource(String key) throws IOException {
        // Buffered fully into memory rather than streamed - CVs are capped well under 10MB (see
        // app.cv.upload.max-size-bytes), so this is cheap, and it sidesteps the double-read pitfall
        // Spring's message converters have with hand-wrapped InputStreamResources (the previous S3
        // implementation needed a contentLength() override specifically to work around this).
        byte[] bytes = fetchBytes(key);
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return key;
            }
        };
    }

    @Override
    public <T> T withLocalFile(String key, FileFunction<T> action) throws IOException {
        Path tempFile = Files.createTempFile("cv-", "-" + key);
        try {
            Files.write(tempFile, fetchBytes(key));
            return action.apply(tempFile.toFile());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private byte[] fetchBytes(String key) {
        return client.get().uri("/{bucket}/{key}", bucket, key).retrieve().body(byte[].class);
    }
}
