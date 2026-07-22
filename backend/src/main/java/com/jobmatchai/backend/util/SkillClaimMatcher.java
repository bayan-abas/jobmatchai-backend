package com.jobmatchai.backend.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Lightweight (substring/word-overlap, not real NLP) heuristics for deciding whether a piece of
// free text is making a claim about a specific skill from a job's matched/missing skill lists.
// Shared by every AI-generated free-text surface that must stay consistent with the CANONICAL
// matched/missing skill classification computed once in JobMatchService#applyParsedMatchToScore
// (currently: JobMatchService's own match-detail narrative filter, and ChatConsistencyValidator's
// chat-reply filter) - kept in one place so "does this text mention that skill" is answered the
// same way everywhere a contradiction could otherwise slip through by one call site's heuristic
// being looser than another's.
public final class SkillClaimMatcher {

    private SkillClaimMatcher() {}

    // Generic words a skill/requirement phrase is often padded with that carry no identifying
    // meaning on their own (e.g. "Electronic Health Records (EHR) systems" vs. a narrative bullet
    // that just says "Electronic Health Records") - stripped out so the overlap check below judges
    // two phrasings of the same requirement as the same skill instead of requiring a byte-identical
    // match.
    private static final Set<String> SKILL_MATCH_STOPWORDS = Set.of(
            "and", "or", "the", "a", "an", "of", "in", "for", "with", "to", "is", "are",
            "experience", "knowledge", "proficiency", "skills", "skill", "systems", "system",
            "tools", "software", "understanding", "familiarity", "ability", "certified", "certification");

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

    // Whether `textLower` (already lowercased by the caller) is clearly talking about `skill`. A
    // plain full-phrase substring check is trivially defeated by a paraphrase or a dropped trailing
    // generic word - the stored skill can be a long phrase like "Electronic Health Records (EHR)
    // systems" while free text discussing it is under no obligation to reuse that exact wording, so
    // this falls back from an exact substring match to a significant-word-overlap match.
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
        return hits >= 2 && hits >= Math.ceil(words.size() * 0.6);
    }

    // Phrases pairing a skill mention with claimed absence - gates the "matched skill cited as a
    // gap" checks in callers so text that merely MENTIONS a matched skill for context (e.g. "you
    // have strong Java skills, but this role's use of Kotlin specifically isn't reflected in your
    // CV") isn't wrongly flagged just for naming it. Only text that BOTH names a matched skill AND
    // uses one of these absence phrases is actually claiming that skill is missing.
    private static final List<String> ABSENCE_PHRASES = List.of(
            "lack", "lacking", "missing", "don't have", "doesn't have", "not reflected",
            "not evident", "no experience with", "need to develop", "gap in", "not shown", "absent"
    );

    // Words that, appearing shortly before an ABSENCE_PHRASES match, flip its meaning from a real
    // gap claim into an explicit denial of one - e.g. "there is no evidence of any missing skills"
    // CONFIRMS there is no gap, even though it contains "missing". Found in production testing: a
    // genuinely gap-free summary was getting every skill it named incorrectly flagged as "claimed
    // absent" by a bare ABSENCE_PHRASES substring check, because it never accounted for the
    // sentence negating its own absence phrase.
    private static final List<String> NEGATION_WORDS = List.of("no ", "not ", "n't", "never ", "without ", "none ");

    public static boolean hasGenuineAbsenceClaim(String textLower) {
        if (textLower == null) {
            return false;
        }
        for (String phrase : ABSENCE_PHRASES) {
            int idx = textLower.indexOf(phrase);
            while (idx >= 0) {
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
