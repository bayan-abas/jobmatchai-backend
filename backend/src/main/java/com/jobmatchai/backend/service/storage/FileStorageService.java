package com.jobmatchai.backend.service.storage;

import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;

public interface FileStorageService {

    // שומר קובץ בשירות האחסון (מקומי או Supabase, תלוי בהגדרת האפליקציה) תחת מפתח ייחודי
    void store(File source, String key) throws IOException;

    // בודק אם קיים קובץ שמור עם המפתח הזה
    boolean exists(String key);

    // מוחק את הקובץ השמור עם המפתח הזה
    void delete(String key);

    // טוען את הקובץ כמשאב שאפשר להחזיר כתגובת הורדה ב-HTTP
    Resource loadAsResource(String key) throws IOException;

    // מביא את הקובץ כקובץ מקומי זמני ומריץ עליו פעולה נתונה
    <T> T withLocalFile(String key, FileFunction<T> action) throws IOException;

    @FunctionalInterface
    interface FileFunction<T> {
        T apply(File file) throws IOException;
    }
}
