package com.jobmatchai.backend.util;

public final class MatchLabelUtil {

    private MatchLabelUtil() {}

    // ממיר את הציון המספרי לתווית מילולית שמוצגת למשתמש (Excellent/Strong/Moderate/Weak/Poor)
    public static String fromScore(Integer score) {
        if (score == null) {
            return null;
        }

        if (score >= 85) return "Excellent Match";
        if (score >= 70) return "Strong Match";
        if (score >= 50) return "Moderate Match";
        if (score >= 30) return "Weak Match";
        return "Poor Match";
    }

    // ממיר את הציון להמלצת החלטה (accept/consider/reject) לפי סף כללי, בלי קשר לתווית התצוגה
    public static String recommendationFromScore(Integer score) {
        if (score == null) {
            return null;
        }

        if (score >= 70) return "accept";
        if (score >= 50) return "consider";
        return "reject";
    }
}
