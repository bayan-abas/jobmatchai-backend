package com.jobmatchai.backend.util;

import java.util.Map;
import java.util.Set;

public final class CvFileValidator {

    private CvFileValidator() {}

    private static final Map<String, String> ALLOWED_EXTENSION_TO_CONTENT_TYPE = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "pdf";
        }
        if (lower.endsWith(".docx")) {
            return "docx";
        }
        return null;
    }

    public static boolean contentMatchesExtension(String extension, String detectedContentType) {
        String expected = ALLOWED_EXTENSION_TO_CONTENT_TYPE.get(extension);
        return expected != null && expected.equals(detectedContentType);
    }

    public static Set<String> allowedExtensions() {
        return ALLOWED_EXTENSION_TO_CONTENT_TYPE.keySet();
    }
}
