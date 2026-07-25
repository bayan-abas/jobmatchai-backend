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

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    @Value("${app.upload.dir:uploads/cvs/}")
    private String uploadDir;

    private Path uploadPath() {
        return Paths.get(System.getProperty("user.dir")).resolve(uploadDir).normalize().toAbsolutePath();
    }

    private Path resolve(String key) {
        // מניעת path traversal - לוודא שהנתיב לא בורח מתיקיית ה-uploads (למשל key עם "../")
        Path resolved = uploadPath().resolve(key).normalize().toAbsolutePath();
        if (!resolved.startsWith(uploadPath())) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return resolved;
    }

    // שומר את הקובץ בדיסק המקומי בנתיב שנגזר מה-key, ודורס אם כבר קיים קובץ כזה
    @Override
    public void store(File source, String key) throws IOException {
        Path destination = resolve(key);
        Files.createDirectories(destination.getParent());
        Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
    }

    // בודק אם קובץ עם המפתח הזה קיים בתיקיית ההעלאות
    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    // מוחק את הקובץ מהדיסק המקומי
    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            // לא קריטי אם המחיקה נכשלת - לא רוצים להפיל את הבקשה בגלל זה
        }
    }

    // עוטף את הקובץ המקומי כ-Resource של Spring כדי שאפשר יהיה להחזיר אותו כתגובת HTTP
    @Override
    public Resource loadAsResource(String key) throws IOException {
        return new UrlResource(resolve(key).toUri());
    }

    // מריץ פעולה נתונה ישירות על הקובץ המקומי - באחסון מקומי הקובץ כבר נמצא על הדיסק
    @Override
    public <T> T withLocalFile(String key, FileFunction<T> action) throws IOException {

        return action.apply(resolve(key).toFile());
    }
}
