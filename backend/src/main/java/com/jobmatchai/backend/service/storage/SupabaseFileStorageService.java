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

    // מאתחל את הלקוח ל-REST API של Supabase Storage עם כותרות האימות הנדרשות
    @PostConstruct
    private void init() {
        client = RestClient.builder()
                .baseUrl(supabaseUrl + "/storage/v1/object")

                .defaultHeader("apikey", serviceRoleKey)
                .defaultHeader("Authorization", "Bearer " + serviceRoleKey)
                .build();
    }

    // מעלה את הקובץ ל-bucket של Supabase תחת המפתח הנתון (PUT מחליף קובץ קיים)
    @Override
    public void store(File source, String key) throws IOException {
        byte[] bytes = Files.readAllBytes(source.toPath());

        client.put()
                .uri("/{bucket}/{key}", bucket, key)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes)
                .retrieve()
                .toBodilessEntity();
    }

    // בודק קיום קובץ ב-Supabase באמצעות בקשת HEAD - תשובת 404 אומרת שהוא לא קיים
    @Override
    public boolean exists(String key) {
        try {
            client.head().uri("/{bucket}/{key}", bucket, key).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        }
    }

    // מוחק את הקובץ מ-Supabase
    @Override
    public void delete(String key) {
        try {
            client.delete().uri("/{bucket}/{key}", bucket, key).retrieve().toBodilessEntity();
        } catch (Exception e) {
            // מתעלמים בכוונה - מחיקה צריכה להיות אידמפוטנטית גם אם הקובץ כבר לא שם
        }
    }

    // מוריד את תוכן הקובץ מ-Supabase ועוטף אותו כ-Resource עם שם הקובץ המקורי
    @Override
    public Resource loadAsResource(String key) throws IOException {

        byte[] bytes = fetchBytes(key);
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return key;
            }
        };
    }

    // מוריד את הקובץ מ-Supabase לקובץ זמני מקומי, מריץ עליו את הפעולה הנתונה ואז מנקה אותו
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

    // מוריד את הבייטים הגולמיים של הקובץ מ-Supabase לפי המפתח
    private byte[] fetchBytes(String key) {
        return client.get().uri("/{bucket}/{key}", bucket, key).retrieve().body(byte[].class);
    }
}
