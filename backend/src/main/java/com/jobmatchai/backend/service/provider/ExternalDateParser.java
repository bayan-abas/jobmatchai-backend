package com.jobmatchai.backend.service.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class ExternalDateParser {

    private static final DateTimeFormatter[] PATTERNS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    };

    private ExternalDateParser() {
    }

    static LocalDateTime parse(String raw) {
        // כל provider חיצוני מחזיר תאריכים בפורמט קצת שונה - מנסים את כולם בסדר עד שאחד מצליח
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        try {
            return Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {

        }

        for (DateTimeFormatter pattern : PATTERNS) {
            try {
                return LocalDateTime.parse(text, pattern);
            } catch (DateTimeParseException ignored) {

            }
        }

        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
