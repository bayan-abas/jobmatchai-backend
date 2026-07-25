package com.jobmatchai.backend.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SkillClaimMatcher {

    private SkillClaimMatcher() {}

    private static final Set<String> SKILL_MATCH_STOPWORDS = Set.of(
            "and", "or", "the", "a", "an", "of", "in", "for", "with", "to", "is", "are",
            "experience", "knowledge", "proficiency", "skills", "skill", "systems", "system",
            "tools", "software", "understanding", "familiarity", "ability", "certified", "certification");

    // מפרק ביטוי מיומנות למילים משמעותיות בלבד, אחרי סינון מילות קישור וסטופ-וורדס
    public static List<String> significantSkillWords(String phrase) {
        if (phrase == null) {
            return List.of();
        }
        String withoutParens = phrase.replaceAll("\\([^)]*\\)", " ");
        String[] tokens = withoutParens.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> words = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() >= 3 && !SKILL_MATCH_STOPWORDS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    // בודק אם טקסט (כמו קו"ח) מזכיר מיומנות מסוימת, גם אם לא בניסוח מדויק אלא ברוב המילים המשמעותיות שלה
    public static boolean mentionsSkill(String textLower, String skill) {
        if (skill == null || skill.isBlank() || textLower == null) {
            return false;
        }
        String skillLower = skill.toLowerCase(Locale.ROOT).trim();
        if (skillLower.length() >= 3 && textLower.contains(skillLower)) {
            return true;
        }
        List<String> words = significantSkillWords(skill);
        if (words.isEmpty()) {
            return false;
        }
        long hits = words.stream().filter(textLower::contains).count();
        if (words.size() == 1) {
            return hits == 1;
        }
        // 60% מהמילים המשמעותיות מספיק כדי לא לפספס ניסוחים שונים לאותה מיומנות
        return hits >= 2 && hits >= Math.ceil(words.size() * 0.6);
    }

    private static final List<String> ABSENCE_PHRASES = List.of(
            "lack", "lacking", "missing", "don't have", "doesn't have", "not reflected",
            "not evident", "no experience with", "need to develop", "gap in", "not shown", "absent"
    );

    private static final List<String> NEGATION_WORDS = List.of("no ", "not ", "n't", "never ", "without ", "none ");

    // מזהה אם הטקסט טוען בפועל שחסרה מיומנות (כמו "lacking experience"), ולא רק מזכיר אותה בשלילה כפולה
    public static boolean hasGenuineAbsenceClaim(String textLower) {
        if (textLower == null) {
            return false;
        }
        for (String phrase : ABSENCE_PHRASES) {
            int idx = textLower.indexOf(phrase);
            while (idx >= 0) {
                // בודקים 25 תווים אחורה כדי לתפוס שלילה כמו "not lacking" - אחרת זה נחשב בטעות לטענת חוסר
                String preceding = textLower.substring(Math.max(0, idx - 25), idx);
                if (NEGATION_WORDS.stream().noneMatch(preceding::contains)) {
                    return true;
                }
                idx = textLower.indexOf(phrase, idx + 1);
            }
        }
        return false;
    }
}
