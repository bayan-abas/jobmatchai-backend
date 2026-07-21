package com.jobmatchai.backend.service.provider;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

// Best-effort publication-date parsing shared by every ExternalJobProvider - each provider
// documents its posted/updated-date field in a different, loosely-specified format (a full ISO
// instant with a trailing Z, a plain "yyyy-MM-dd HH:mm:ss", or just a date), and none of them
// guarantee the field is even present on every listing. Deliberately never throws - a job whose
// publish date this app can't parse should still import successfully with publishedAt left null,
// not abort the whole import cycle.
final class ExternalDateParser {

    private static final DateTimeFormatter[] PATTERNS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
    };

    private ExternalDateParser() {
    }

    static LocalDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();

        try {
            return Instant.parse(text).atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Not a full ISO instant (no trailing Z/offset) - fall through to the plainer formats.
        }

        for (DateTimeFormatter pattern : PATTERNS) {
            try {
                return LocalDateTime.parse(text, pattern);
            } catch (DateTimeParseException ignored) {
                // Try the next candidate format.
            }
        }

        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
