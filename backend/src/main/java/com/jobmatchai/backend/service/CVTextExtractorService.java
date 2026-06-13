package com.jobmatchai.backend.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.File;
import java.nio.file.Files;

@Service
public class CVTextExtractorService {

    private final Tika tika = new Tika();

    public String extractText(File file) {
        try {
            String tikaText = cleanExtractedText(tika.parseToString(file));
            String embeddedText = extractEmbeddedWordHtml(file);

            return isBetterExtraction(embeddedText, tikaText) ? embeddedText : tikaText;
        } catch (Exception e) {
            throw new RuntimeException("Failed to extract text from CV file: " + e.getMessage());
        }
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
            cleaned = cleaned
                    .replaceAll("(?is)<!--.*?-->", " ")
                    .replaceAll("(?is)<(script|style|head|xml)[^>]*>.*?</\\1>", " ")
                    .replaceAll("(?is)<[^>]+>", " ");
            cleaned = HtmlUtils.htmlUnescape(cleaned);
        }

        return cleaned
                .replaceAll("(?m)^\\s*(mso-|font-|margin|padding|border|style=|class=|span\\b|td\\b|tr\\b).*", " ")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
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

        score += Math.min(countMatches(text, "[\\p{IsHebrew}\\p{IsArabic}A-Za-z]"), 300);
        score += countContains(lower, "\u05e7\u05d5\u05e8\u05d5\u05ea", "\u05d7\u05d9\u05d9\u05dd", "\u05e0\u05d9\u05e1\u05d9\u05d5\u05df", "\u05ea\u05e2\u05e1\u05d5\u05e7\u05ea\u05d9", "\u05d4\u05e9\u05db\u05dc\u05d4", "\u05de\u05e0\u05d4\u05dc", "\u05e2\u05d1\u05d5\u05d3\u05d4", "\u05d1\u05e0\u05d9\u05d9\u05df") * 25;
        score += countContains(lower, "resume", "experience", "education", "skills", "work", "employment") * 20;
        score += countMatches(text, "\\b(19|20)\\d{2}\\b") * 8;

        score -= countContains(lower, "mso-", "font-family", "stylesheet", "normal.dot", "times new roman") * 25;
        score -= Math.min(countMatches(text, "(?m)^\\s*\\d+(\\.\\d+)?\\s*$"), 80);

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

    private int countMatches(String text, String pattern) {
        return (int) java.util.regex.Pattern.compile(pattern)
                .matcher(text)
                .results()
                .count();
    }
}