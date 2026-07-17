package com.jobmatchai.backend.util;

import java.util.Map;
import java.util.Set;

// Server-side CV upload validation (see CVController#uploadCV). Enforces exactly the file types
// the security audit asked for - PDF and DOCX - by checking BOTH the claimed extension AND the
// actual file content (magic bytes, detected via Tika - see
// CVTextExtractorService#detectContentType). An executable or script renamed to end in
// .pdf/.docx has an allowed extension but the wrong detected content type, and is rejected
// either way; a genuine PDF renamed to .docx (or vice versa) is rejected too, since content and
// claimed extension must agree.
public final class CvFileValidator {

    private CvFileValidator() {}

    private static final Map<String, String> ALLOWED_EXTENSION_TO_CONTENT_TYPE = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    // Null means "not one of the allowed extensions".
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
