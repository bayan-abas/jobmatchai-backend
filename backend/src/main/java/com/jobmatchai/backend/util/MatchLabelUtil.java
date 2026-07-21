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

    // A fixed-vocabulary hiring-decision category ("accept"/"consider"/"reject"), also a pure
    // function of the same match score, derived at read time for the same reason fromScore is:
    // it can never drift out of sync with the score it describes, and it can never disagree with
    // itself between two places that both show it. This used to be computed independently in the
    // frontend (a bare score-threshold if/else in a UI file, with no backend equivalent at all) -
    // moved here so the frontend has exactly one authoritative source for this judgment, the same
    // as every other piece of AI-derived match data, and only ever maps this fixed category to a
    // localized label - it never decides the category itself. Thresholds intentionally match
    // fromScore's own tiers (>=70 covers "Excellent"/"Strong Match", 50-69 is "Moderate Match",
    // below 50 covers "Weak"/"Poor Match") so the recommendation and the label can never disagree
    // about which side of a boundary a given score falls on.
    public static String recommendationFromScore(Integer score) {
        if (score == null) {
            return null;
        }

        if (score >= 70) return "accept";
        if (score >= 50) return "consider";
        return "reject";
    }
}
