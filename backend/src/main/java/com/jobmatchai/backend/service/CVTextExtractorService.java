package com.jobmatchai.backend.service;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.regex.Pattern;

@Service
public class CVTextExtractorService {

    private static final Logger log = LoggerFactory.getLogger(CVTextExtractorService.class);

    private final Tika tika = new Tika();

    // Sniffs the actual file format from its content (magic bytes / container structure), not
    // from any filename - deliberately does NOT pass a filename hint to Tika, since a hint would
    // let a mismatched-but-plausible extension influence detection for ambiguous content,
    // defeating the point of checking real content instead of the claimed extension.
    public String detectContentType(InputStream inputStream) throws IOException {
        return tika.detect(inputStream);
    }

    private static final Pattern HTML_COMMENT_PATTERN = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern HTML_SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style|head|xml)[^>]*>.*?</\\1>");
    private static final Pattern HTML_ANY_TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern CSS_LIKE_PATTERN = Pattern.compile("(?m)^\\s*(mso-|font-|margin|padding|border|style=|class=|span\\b|td\\b|tr\\b).*");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern NEWLINE_PATTERN = Pattern.compile("\\n{3,}");

    private static final Pattern LETTERS_PATTERN = Pattern.compile("[\\p{IsHebrew}\\p{IsArabic}A-Za-z]");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern NUMBER_ONLY_PATTERN = Pattern.compile("(?m)^\\s*\\d+(\\.\\d+)?\\s*$");

    public String extractText(File file) {
        try {
            String tikaText = cleanExtractedText(tika.parseToString(file));

            // The raw-bytes-as-UTF-16LE rescue below only makes sense for Word-family binary
            // formats, where Word can store paragraph text as UTF-16LE runs recoverable even when
            // the container itself parses badly - it was never meaningful for PDF, whose binary
            // structure decoded as UTF-16LE is essentially never blank (mostly replacement
            // characters and stray symbols), yet isBetterExtraction() below unconditionally
            // prefers ANY non-blank candidate over a blank current result. Applying it to every
            // file type meant a PDF that genuinely has no extractable text (scanned/image-only, or
            // fonts with no Unicode mapping) had its correctly-blank result silently replaced with
            // meaningless byte-soup, which then passed CVController's isBlank() check and got sent
            // to the AI CV-classifier as if it were real content - masking the true "no readable
            // text" condition behind a confusing "not a CV" rejection instead.
            String tikaTextForLogging = tikaText;
            String extractedText = tikaText;
            if (isWordDocument(file)) {
                String embeddedText = extractEmbeddedWordHtml(file);
                if (isBetterExtraction(embeddedText, tikaText)) {
                    extractedText = embeddedText;
                }
            }

            log.info("CV text extraction: file={} tikaLength={} finalLength={} preview=\"{}\"",
                    file.getName(),
                    tikaTextForLogging == null ? 0 : tikaTextForLogging.length(),
                    extractedText == null ? 0 : extractedText.length(),
                    previewOf(extractedText));

            return extractedText;
        } catch (Exception e) {
            log.warn("CV text extraction failed for file={}: {}", file.getName(), e.getMessage());
            throw new RuntimeException("Failed to extract text from CV file: " + e.getMessage(), e);
        }
    }

    private boolean isWordDocument(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".docx") || name.endsWith(".doc");
    }

    private static String previewOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = WHITESPACE_PATTERN.matcher(text.replace('\n', ' ')).replaceAll(" ").trim();
        return singleLine.length() > 150 ? singleLine.substring(0, 150) + "..." : singleLine;
    }

    private String extractEmbeddedWordHtml(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String utf16Text = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
            return cleanExtractedText(utf16Text);
        } catch (Exception e) {
            return "";
        }
    }

    private String cleanExtractedText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text
                .replace("\u0000", " ")
                .replace('\u00A0', ' ');

        if (looksLikeHtml(cleaned)) {
            cleaned = HTML_COMMENT_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = HTML_SCRIPT_STYLE_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = HTML_ANY_TAG_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = HtmlUtils.htmlUnescape(cleaned != null ? cleaned : "");
        }

        cleaned = CSS_LIKE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ");
        cleaned = NEWLINE_PATTERN.matcher(cleaned).replaceAll("\n\n");
        
        return cleaned.trim();
    }

    private boolean looksLikeHtml(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<html")
                || lower.contains("<body")
                || lower.contains("<span")
                || lower.contains("<p ")
                || lower.contains("</p>")
                || lower.contains("mso-");
    }

    private boolean isBetterExtraction(String candidate, String current) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (current == null || current.isBlank()) {
            return true;
        }

        int candidateScore = extractionQualityScore(candidate);
        int currentScore = extractionQualityScore(current);

        return candidateScore > currentScore + 20;
    }

    private int extractionQualityScore(String text) {
        String lower = text.toLowerCase();
        int score = 0;

        score += Math.min(countMatches(text, LETTERS_PATTERN), 300);
        score += countContains(lower, "\u05e7\u05d5\u05e8\u05d5\u05ea", "\u05d7\u05d9\u05d9\u05dd", "\u05e0\u05d9\u05e1\u05d9\u05d5\u05df", "\u05ea\u05e2\u05e1\u05d5\u05e7\u05ea\u05d9", "\u05d4\u05e9\u05db\u05dc\u05d4", "\u05de\u05e0\u05d4\u05dc", "\u05e2\u05d1\u05d5\u05d3\u05d4", "\u05d1\u05e0\u05d9\u05d9\u05df") * 25;
        score += countContains(lower, "resume", "experience", "education", "skills", "work", "employment") * 20;
        score += countMatches(text, YEAR_PATTERN) * 8;

        score -= countContains(lower, "mso-", "font-family", "stylesheet", "normal.dot", "times new roman") * 25;
        score -= Math.min(countMatches(text, NUMBER_ONLY_PATTERN), 80);

        return score;
    }

    private int countContains(String text, String... values) {
        int count = 0;
        for (String value : values) {
            if (text.contains(value.toLowerCase())) {
                count++;
            }
        }
        return count;
    }

    private int countMatches(String text, Pattern pattern) {
        return (int) pattern.matcher(text)
                .results()
                .count();
    }
}