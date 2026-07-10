package com.jobmatchai.backend.util;

// A candidate_ai_summary match label is a pure function of its match score - deriving it
// here at read time (instead of persisting it) means it can never drift out of sync with
// the score it describes.
public final class MatchLabelUtil {

    private MatchLabelUtil() {}

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
}
