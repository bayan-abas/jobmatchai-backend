package com.jobmatchai.backend.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MatchScoreCalculator {

    public enum ComponentKey {
        FIELD_RELEVANCE, REQUIRED_SKILLS, EXPERIENCE, EDUCATION, CERTIFICATION, LOCATION
    }

    // משקלים שנקבעו ידנית לפי ניסוי וטעייה - סכומם 1.0, לא לשנות בלי לבדוק שוב את כל הבדיקות
    private static final Map<ComponentKey, Double> WEIGHTS = Map.of(
            ComponentKey.FIELD_RELEVANCE, 0.30,
            ComponentKey.REQUIRED_SKILLS, 0.30,
            ComponentKey.EXPERIENCE, 0.20,
            ComponentKey.EDUCATION, 0.10,
            ComponentKey.CERTIFICATION, 0.05,
            ComponentKey.LOCATION, 0.05
    );

    public record Component(ComponentKey key, Integer score) {}

    public record WeightedResult(int overallPercent, Map<ComponentKey, Integer> componentPercents) {}

    private MatchScoreCalculator() {
    }

    // מחשב את אחוז ההתאמה הכולל כממוצע משוקלל של כל הקטגוריות, תוך התעלמות מקטגוריות שאין להן ציון
    public static WeightedResult compute(List<Component> components) {
        double totalWeight = 0;
        double weightedSum = 0;
        Map<ComponentKey, Integer> componentPercents = new LinkedHashMap<>();

        for (Component component : components) {
            Integer score = component.score() == null ? null : clamp(component.score());
            componentPercents.put(component.key(), score);

            if (score == null) {
                continue;
            }

            double weight = WEIGHTS.get(component.key());
            totalWeight += weight;
            weightedSum += score * weight;
        }

        int overall = totalWeight <= 0 ? 0 : (int) Math.round(weightedSum / totalWeight);
        return new WeightedResult(clamp(overall), componentPercents);
    }

    // מחשב ציון כישורים כאשר כישורי חובה נספרים במלואם וכישורים מועדפים בחצי משקל
    public static Integer computeSkillsScore(
            int matchedMandatory, int missingMandatory, int matchedPreferred, int missingPreferred) {
        int totalMandatory = matchedMandatory + missingMandatory;
        int totalPreferred = matchedPreferred + missingPreferred;

        if (totalMandatory == 0 && totalPreferred == 0) {
            return null;
        }

        double weightedTotal = totalMandatory + (totalPreferred * 0.5);
        double weightedMatched = matchedMandatory + (matchedPreferred * 0.5);

        return clamp((int) Math.round(100.0 * weightedMatched / weightedTotal));
    }

    // ממיר את דרגת הקרבה בין המקצוע של המועמד למשרה לציון מספרי - סולם ידני שכיילנו לפי תחושת בטן, לא נוסחה
    public static int scoreFieldRelevance(String closeness) {
        if (closeness == null) {
            return 0;
        }
        return switch (closeness.trim().toLowerCase()) {
            case "same_role" -> 95;
            case "same_specialization" -> 80;

            case "closely_related" -> 65;
            case "same_broad_field" -> 55;

            case "related" -> 40;

            case "general_vocational_role" -> 25;
            default -> 0;

        };
    }

    // ממיר רמה טקסטואלית (כמו "senior_level") למספר סידורי לפי הרשימה המסודרת, לצורך השוואה בין רמות
    private static int rank(String value, List<String> orderedLevels) {
        if (value == null) {
            return 0;
        }
        int index = orderedLevels.indexOf(value.trim().toLowerCase());
        return Math.max(index, 0);
    }

    private static final List<String> EXPERIENCE_LEVELS = List.of("none", "entry_level", "mid_level", "senior_level");
    private static final List<String> REQUIRED_EXPERIENCE_LEVELS = List.of("entry", "mid", "senior");

    public static int scoreExperience(String candidateExperienceLevel, String requiredLevel, boolean sameSpecificRole) {
        return scoreExperience(candidateExperienceLevel, requiredLevel, sameSpecificRole, false, false);
    }

    // משווה בין רמת הניסיון של המועמד לנדרש במשרה, ומעניש ניסיון בתפקיד/סוג לא מדויק
    public static int scoreExperience(
            String candidateExperienceLevel, String requiredLevel, boolean sameSpecificRole,
            boolean requiresSpecificType, boolean candidateHasSpecificType) {
        int candidateRank = rank(candidateExperienceLevel, EXPERIENCE_LEVELS);
        // אם זה לא בדיוק התפקיד/התמחות של המועמד, מורידים דרגת ניסיון אחת - ניסיון בתחום קרוב "שווה" פחות
        if (!sameSpecificRole) {
            candidateRank = Math.max(0, candidateRank - 1);
        }

        int requiredRank = REQUIRED_EXPERIENCE_LEVELS.indexOf(
                requiredLevel == null ? "" : requiredLevel.trim().toLowerCase()) + 1;
        if (requiredRank <= 0) {
            requiredRank = 1;
        }

        int amountScore = candidateRank >= requiredRank
                ? 100
                : clamp(100 - (requiredRank - candidateRank) * 40);

        if (!requiresSpecificType || candidateHasSpecificType) {
            return amountScore;
        }
        return clamp((int) Math.round(amountScore * 0.5));
    }

    private static final List<String> EDUCATION_EVIDENCE_LEVELS = List.of("none", "general", "relevant_degree");

    // בודק אם רמת ההשכלה של המועמד מכסה את הנדרש במשרה, עם קנס יחסי אם היא נמוכה מדי
    public static int scoreEducation(String candidateEducationEvidence, String requiredLevel) {
        int candidateRank = rank(candidateEducationEvidence, EDUCATION_EVIDENCE_LEVELS);
        int requiredRank = "relevant_degree".equalsIgnoreCase(requiredLevel) ? 2 : 1;

        if (candidateRank >= requiredRank) {
            return 100;
        }
        return clamp(100 - (requiredRank - candidateRank) * 45);
    }

    private static final List<String> CERT_EVIDENCE_LEVELS = List.of("none", "general", "field_relevant");
    private static final List<String> LICENSE_EVIDENCE_LEVELS = List.of("none", "in_progress", "licensed");

    // מדרג הסמכות/רישיונות של המועמד מול דרישת המשרה, עם טיפול מיוחד למקרה שדרוש רישיון ספציפי
    public static int scoreCertification(
            String candidateCertificationsEvidence, String candidateLicensesEvidence, String requiredLevel,
            boolean sameSpecificRole) {
        if ("specific_license".equalsIgnoreCase(requiredLevel)) {
            int licenseRank = rank(candidateLicensesEvidence, LICENSE_EVIDENCE_LEVELS);
            // תפקיד שונה מהמקצוע של המועמד - גם רישיון קיים לא מאומת בשביל התפקיד הזה, אז תקרה נמוכה
            if (!sameSpecificRole) {
                return licenseRank >= 2 ? 40 : (licenseRank == 1 ? 25 : 15);
            }
            if (licenseRank >= 2) return 100;
            if (licenseRank == 1) return 55;
            return 15;
        }

        int certRank = rank(candidateCertificationsEvidence, CERT_EVIDENCE_LEVELS);
        int licenseRank = rank(candidateLicensesEvidence, LICENSE_EVIDENCE_LEVELS);
        int effectiveRank = Math.max(certRank, licenseRank > 0 ? 2 : 0);

        return effectiveRank >= 1 ? 100 : 50;
    }

    // נותן ציון מיקום מלא רק כשהמשרה מוגדרת כעבודה מרחוק, אחרת לא רלוונטי לחישוב (מחזיר null)
    public static Integer scoreLocation(String jobType, String jobLocation) {
        String combined = ((jobType == null ? "" : jobType) + " " + (jobLocation == null ? "" : jobLocation))
                .toLowerCase();
        if (combined.contains("remote") || combined.contains("work from home") || combined.contains("wfh")) {
            return 100;
        }
        return null;
    }

    public static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
