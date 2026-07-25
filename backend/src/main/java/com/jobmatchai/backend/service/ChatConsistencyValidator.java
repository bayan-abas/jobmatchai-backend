package com.jobmatchai.backend.service;

import com.jobmatchai.backend.service.AIChatContextService.CanonicalFact;
import com.jobmatchai.backend.util.SkillClaimMatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatConsistencyValidator {

    private static final Logger log = LoggerFactory.getLogger(ChatConsistencyValidator.class);

    private static final Pattern HEADING_PATTERN = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("\\*\\*(\\d{1,3})%\\*\\*");

    private static final List<String> NEGATIVE_HEADING_KEYWORDS = List.of("missing", "weakness", "gap", "concern");

    public record ValidationResult(String reply, List<String> issues, boolean critical) {}

    // עובר על תשובת הצ'אט חלק-חלק, מתקן אחוזי התאמה שגויים לפי הנתונים האמיתיים, ומוריד בולטים שסותרים כישורים תואמים/חסרים ידועים
    public ValidationResult validate(String reply, List<CanonicalFact> facts) {
        if (reply == null || reply.isBlank() || facts == null || facts.isEmpty()) {
            return new ValidationResult(reply, List.of(), false);
        }

        List<String> issues = new ArrayList<>();
        boolean[] critical = {false};

        List<Block> blocks = splitIntoBlocks(reply);

        CanonicalFact runningFocus = facts.size() == 1 ? facts.get(0) : null;
        for (Block block : blocks) {
            CanonicalFact blockFact = matchFactInText(block.headingText() + "\n" + String.join("\n", block.bodyLines()), facts);
            if (blockFact != null) {
                runningFocus = blockFact;
            }
            block.focus = runningFocus;
        }

        StringBuilder rebuilt = new StringBuilder();
        for (Block block : blocks) {
            boolean isMatchScoreHeading = block.headingText() != null
                    && block.headingText().trim().equalsIgnoreCase("Match Score");
            boolean isNegativeHeading = block.headingText() != null
                    && NEGATIVE_HEADING_KEYWORDS.stream().anyMatch(block.headingText().toLowerCase(Locale.ROOT)::contains);

            List<String> newBody = new ArrayList<>();
            int originalBulletCount = 0;
            int keptBulletCount = 0;

            for (String line : block.bodyLines()) {
                String trimmed = line.trim();
                boolean isBullet = trimmed.startsWith("-");
                if (isBullet) {
                    originalBulletCount++;
                }

                if (trimmed.isEmpty()) {
                    newBody.add(line);
                    continue;
                }

                CanonicalFact lineFact = matchFactInText(trimmed, facts);
                CanonicalFact active = lineFact != null ? lineFact : block.focus;
                if (active == null) {
                    newBody.add(line);
                    if (isBullet) {
                        keptBulletCount++;
                    }
                    continue;
                }

                String corrected = correctPercent(line, active, issues);
                if (isMatchScoreHeading && !corrected.equals(line)) {
                    critical[0] = true;
                }
                line = corrected;

                if (isBullet) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    boolean drop = false;

                    if (!isNegativeHeading) {
                        boolean praisesMissingSkill = active.missingSkills().stream()
                                .anyMatch(skill -> SkillClaimMatcher.mentionsSkill(lower, skill));
                        if (praisesMissingSkill) {
                            issues.add("dropped a reply bullet praising '" + active.jobTitle()
                                    + "'-missing skill as a strength (contradicts the validated missing-skills list)");
                            log.info("chat-reply-filtered reason=praises-missing-skill jobId={}", active.jobId());
                            drop = true;
                        }
                    }

                    if (!drop) {
                        boolean flagsMatchedSkillAsAbsent = SkillClaimMatcher.hasGenuineAbsenceClaim(lower)
                                && active.matchedSkills().stream().anyMatch(skill -> SkillClaimMatcher.mentionsSkill(lower, skill));
                        if (flagsMatchedSkillAsAbsent) {
                            issues.add("dropped a reply bullet flagging a '" + active.jobTitle()
                                    + "'-matched skill as a gap (contradicts the validated matched-skills list)");
                            log.info("chat-reply-filtered reason=flags-matched-skill-as-gap jobId={}", active.jobId());
                            drop = true;
                        }
                    }

                    if (drop) {
                        continue;
                    }
                    keptBulletCount++;
                }

                newBody.add(line);
            }

            boolean sectionEmptied = originalBulletCount > 0 && keptBulletCount == 0
                    && newBody.stream().allMatch(String::isBlank);

            if (block.headingText() != null && !sectionEmptied) {
                rebuilt.append("## ").append(block.headingText()).append("\n");
            } else if (block.headingText() != null) {
                issues.add("dropped the entire '" + block.headingText() + "' section - every claim in it contradicted validated data");
            }
            if (!sectionEmptied) {
                for (String line : newBody) {
                    rebuilt.append(line).append("\n");
                }
            }
        }

        String correctedReply = rebuilt.toString().stripTrailing();
        return new ValidationResult(correctedReply, issues, critical[0]);
    }

    // מחליף בשורה כל אחוז שגוי (**X%**) באחוז ההתאמה האמיתי שכבר חושב עבור אותה משרה
    private String correctPercent(String line, CanonicalFact fact, List<String> issues) {
        if (fact.matchPercent() == null) {
            return line;
        }
        Matcher matcher = PERCENT_PATTERN.matcher(line);
        StringBuilder result = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            int claimed = Integer.parseInt(matcher.group(1));
            if (claimed != fact.matchPercent()) {
                changed = true;
                matcher.appendReplacement(result, Matcher.quoteReplacement("**" + fact.matchPercent() + "%**"));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        if (changed) {
            issues.add("corrected a match-percentage claim for '" + fact.jobTitle()
                    + "' to the validated " + fact.matchPercent() + "%");
        }
        return result.toString();
    }

    // מזהה לאיזו עובדה קנונית (איזו משרה/מועמד) קטע טקסט מסוים מתייחס, לפי הופעת שם המשרה/המועמד בטקסט
    private CanonicalFact matchFactInText(String text, List<CanonicalFact> facts) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        CanonicalFact found = null;
        for (CanonicalFact fact : facts) {
            boolean titleHit = fact.jobTitle() != null && fact.jobTitle().trim().length() >= 3
                    && lower.contains(fact.jobTitle().toLowerCase(Locale.ROOT).trim());
            boolean nameHit = fact.candidateName() == null
                    || (fact.candidateName().trim().length() >= 3 && lower.contains(fact.candidateName().toLowerCase(Locale.ROOT).trim()));
            if (titleHit && nameHit) {
                // שני facts שונים תואמים באותו קטע טקסט - לא ברור למי זה שייך, אז עדיף לא לתקן בכלל מאשר לתקן לא נכון
                if (found != null && !found.equals(fact)) {
                    return null;
                }
                found = fact;
            }
        }
        return found;
    }

    private static final class Block {
        private final String headingText;
        private final List<String> bodyLines;
        private CanonicalFact focus;

        private Block(String headingText, List<String> bodyLines) {
            this.headingText = headingText;
            this.bodyLines = bodyLines;
        }

        private String headingText() {
            return headingText;
        }

        private List<String> bodyLines() {
            return bodyLines;
        }
    }

    // מפרק את תשובת הצ'אט לבלוקים לפי כותרות markdown ("## ...") כדי שאפשר יהיה לבדוק כל סעיף בנפרד
    private List<Block> splitIntoBlocks(String reply) {
        List<Block> blocks = new ArrayList<>();
        String currentHeading = null;
        List<String> currentBody = new ArrayList<>();

        for (String line : reply.split("\n", -1)) {
            Matcher headingMatcher = HEADING_PATTERN.matcher(line.trim());
            if (headingMatcher.matches()) {
                blocks.add(new Block(currentHeading, currentBody));
                currentHeading = headingMatcher.group(1).trim();
                currentBody = new ArrayList<>();
            } else {
                currentBody.add(line);
            }
        }
        blocks.add(new Block(currentHeading, currentBody));
        return blocks;
    }
}
