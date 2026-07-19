package com.jobmatchai.backend.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The single place that turns AI CLASSIFICATIONS into actual PERCENTAGES, and combines those
// percentages into the one overall score shown to the candidate. This is the deterministic half
// of a deliberately split design: the AI only ever picks from a small, fixed vocabulary (e.g.
// "same_broad_field", "senior", "relevant_degree") when reading a CV/job pair - it never invents
// a raw 0-100 number for anything except the final skills classification lists, which are
// themselves cross-checked against the actual CV/job text before being trusted (see
// JobMatchService#validateMatch). Every one of those fixed categories maps to a number ONLY
// here, via a rule table anyone can read and tune - so the same CV+job pair always produces the
// same score, and that score can be explained by pointing at a row in one of these tables,
// instead of "the model said 73 that day."
public final class MatchScoreCalculator {

    public enum ComponentKey {
        FIELD_RELEVANCE, REQUIRED_SKILLS, EXPERIENCE, EDUCATION, CERTIFICATION, LOCATION
    }

    // Weights per the product spec: field relevance 25%, required skills 25%, experience/
    // seniority 20%, education 15%, certifications/licenses 10%, location/work arrangement 5%.
    private static final Map<ComponentKey, Double> WEIGHTS = Map.of(
            ComponentKey.FIELD_RELEVANCE, 0.25,
            ComponentKey.REQUIRED_SKILLS, 0.25,
            ComponentKey.EXPERIENCE, 0.20,
            ComponentKey.EDUCATION, 0.15,
            ComponentKey.CERTIFICATION, 0.10,
            ComponentKey.LOCATION, 0.05
    );

    // A null score means "this job posting doesn't mention this requirement" - not "score it 0".
    // Excluding it from both the numerator and denominator is what redistributes its weight
    // proportionally across whichever components ARE present, instead of unfairly punishing a
    // job for not stating an experience/education/certification/location requirement at all.
    public record Component(ComponentKey key, Integer score) {}

    public record WeightedResult(int overallPercent, Map<ComponentKey, Integer> componentPercents) {}

    private MatchScoreCalculator() {
    }

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

    // Deterministic, backend-computed skills component: skills are classified as mandatory or
    // preferred (from the job posting's own wording), and a missing PREFERRED skill costs half
    // as much as a missing MANDATORY one - "apply a smaller penalty for optional/preferred
    // requirements" per the spec, implemented as a fixed, reproducible weighting rather than
    // left to the AI to eyeball a single number.
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

    // fieldRelationCloseness is the ONLY classification the AI makes that is inherently semantic
    // (how closely related is THIS specialization to THIS job's specialization, within a field
    // both are already agreed to share) - there is no structured data to compute it from
    // mechanically the way experience/education/certification below can be. Restricting the AI
    // to picking one of exactly four labels (instead of inventing an arbitrary 0-100 number)
    // still gets a deterministic, explainable score out of it: the same label always maps to
    // the same fixed number, so the source of any instability is narrowed to "did the AI pick a
    // different label," never "did the AI feel like a different number this time."
    public static int scoreFieldRelevance(String closeness) {
        if (closeness == null) {
            return 0;
        }
        return switch (closeness.trim().toLowerCase()) {
            case "same_role" -> 95;
            case "same_specialization" -> 80;
            // Backend-assigned (never chosen by the AI directly) from JobMatchService's
            // profession-taxonomy gate, for a curated CLOSELY_RELATED/RELATED edge between two
            // DIFFERENT professions - e.g. Software Engineer <-> QA Automation Engineer, or
            // DevOps Engineer <-> Cloud Engineer. Deliberately below same_specialization (still
            // the candidate's own profession) but above the generic same_broad_field fallback
            // (an AI-judged "shares an industry" guess for professions the taxonomy doesn't
            // cover) - a curated, human-reviewed edge is more trustworthy than that guess.
            case "closely_related" -> 65;
            case "same_broad_field" -> 55;
            // Same taxonomy origin as closely_related above, for the weaker RELATED tier - e.g.
            // Data Analyst <-> Financial Analyst, or Teacher <-> Special Education Teacher's
            // reverse direction. Real transferable skills exist, but less directly than
            // closely_related.
            case "related" -> 40;
            // Backend-assigned (never chosen by the AI directly) when JobMatchService's
            // general-vocational-role override fires: a candidate's specialized field is simply
            // irrelevant to a cashier/cleaner/retail-type role - almost any reliable adult can
            // physically do it, so this must not come back "unrelated" (fieldRelated=false, no
            // score shown at all). But it is deliberately scored BELOW same_broad_field, not
            // above it: found via live testing that 85 here (once higher than
            // same_specialization's 80) let a doctor's CV land at 83-85% overall for a Cashier
            // posting, which is not a meaningful career match just because it's physically
            // achievable. This only measures "not field-related, but doable" - it should never
            // outrank an actual field relation.
            case "general_vocational_role" -> 25;
            default -> 0; // "unrelated" (or anything unrecognized) - should never actually be
                          // scored, since fieldRelated=false already excludes the whole match.
        };
    }

    private static int rank(String value, List<String> orderedLevels) {
        if (value == null) {
            return 0;
        }
        int index = orderedLevels.indexOf(value.trim().toLowerCase());
        return Math.max(index, 0);
    }

    private static final List<String> EXPERIENCE_LEVELS = List.of("none", "entry_level", "mid_level", "senior_level");
    private static final List<String> REQUIRED_EXPERIENCE_LEVELS = List.of("entry", "mid", "senior");

    // requiredLevel is the job's OWN stated seniority expectation (or null if the posting gives
    // no such signal, in which case the caller should treat this component as inapplicable
    // rather than calling this method at all - see JobMatchService). Comparing two fixed-rank
    // vocabularies with a fixed per-rank penalty is what makes "junior applying to a senior
    // posting scores low" a rule anyone can verify, not a number the AI happened to land on.
    //
    // sameSpecificRole (true for fieldRelationCloseness same_role/same_specialization, false for
    // same_broad_field - same pattern already used by scoreCertification below): experienceLevel
    // is a single blanket seniority bucket on the candidate's profile - it does NOT record which
    // field that experience is actually IN. A candidate with senior-level Customer Service
    // experience applying to a QA Engineer job (same_broad_field at most - both "operations"-
    // adjacent, but not the same specific role) should not get full seniority credit as if those
    // years were spent doing QA work. Discounting one rank when the match isn't the candidate's
    // own specific role/specialization is what keeps years of UNRELATED-field experience from
    // inflating a job that only shares a broad field, while still crediting genuinely related
    // (same_role/same_specialization) experience at full value.
    public static int scoreExperience(String candidateExperienceLevel, String requiredLevel, boolean sameSpecificRole) {
        int candidateRank = rank(candidateExperienceLevel, EXPERIENCE_LEVELS);
        if (!sameSpecificRole) {
            candidateRank = Math.max(0, candidateRank - 1);
        }

        // required "entry"/"mid"/"senior" map onto the same 0-3 scale as the candidate's
        // none/entry/mid/senior, shifted by one since there is no "required: none".
        int requiredRank = REQUIRED_EXPERIENCE_LEVELS.indexOf(
                requiredLevel == null ? "" : requiredLevel.trim().toLowerCase()) + 1;
        if (requiredRank <= 0) {
            requiredRank = 1;
        }

        if (candidateRank >= requiredRank) {
            return 100;
        }
        return clamp(100 - (requiredRank - candidateRank) * 40);
    }

    private static final List<String> EDUCATION_EVIDENCE_LEVELS = List.of("none", "general", "relevant_degree");

    // requiredLevel: "any_degree" (posting wants a degree, field unspecified) or
    // "relevant_degree" (posting wants a degree IN this field specifically) - null/"none" means
    // the posting has no degree requirement and this component should be treated as inapplicable
    // by the caller rather than scored here.
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

    // requiredLevel: "general_cert" (posting wants some relevant certification, not a specific
    // legal license) or "specific_license" (posting legally requires a named practice license) -
    // null/"none" means no certification/license requirement; the caller should treat this as
    // inapplicable rather than scoring it. A missing SPECIFIC license still yields a nonzero
    // floor (15) rather than 0, because fieldRelated=true already established this is a
    // same-field candidate - the gap is a real, measurable shortfall, not a field mismatch.
    //
    // sameSpecificRole (true for fieldRelationCloseness same_role/same_specialization, false for
    // same_broad_field): licensesEvidence is a single blanket "licensed: yes/no" flag - it does
    // NOT record which profession the license is for. Found via live verification: a doctor
    // (licensesEvidence="licensed", for MEDICINE) against a Registered Nurse posting requiring a
    // "specific_license" was scoring 100 here, because "licensed"=true alone doesn't distinguish
    // "licensed to practice medicine" from "licensed to practice nursing". When the job is only
    // same_broad_field (a genuinely different specific role/license within the field, not the
    // candidate's own), a "licensed" flag is real but unverified evidence for THIS license, so it
    // is credited far less than when the job is the candidate's own role/specialization.
    public static int scoreCertification(
            String candidateCertificationsEvidence, String candidateLicensesEvidence, String requiredLevel,
            boolean sameSpecificRole) {
        if ("specific_license".equalsIgnoreCase(requiredLevel)) {
            int licenseRank = rank(candidateLicensesEvidence, LICENSE_EVIDENCE_LEVELS);
            if (!sameSpecificRole) {
                return licenseRank >= 2 ? 40 : (licenseRank == 1 ? 25 : 15);
            }
            if (licenseRank >= 2) return 100;
            if (licenseRank == 1) return 55;
            return 15;
        }

        // "general_cert" (or anything else): either a certification OR a license satisfies it -
        // a license is at least as strong evidence of professional credentialing as a
        // certification.
        int certRank = rank(candidateCertificationsEvidence, CERT_EVIDENCE_LEVELS);
        int licenseRank = rank(candidateLicensesEvidence, LICENSE_EVIDENCE_LEVELS);
        int effectiveRank = Math.max(certRank, licenseRank > 0 ? 2 : 0);

        return effectiveRank >= 1 ? 100 : 50;
    }

    // Location is scored purely from the JOB posting's own stated arrangement - there is no
    // persisted candidate location/relocation-preference field to compare it against, so
    // guessing a fit from free-text CV prose would be exactly the kind of unstable, unverifiable
    // number this whole class exists to avoid. A remote/flexible posting is trivially compatible
    // with any candidate; anything requiring a specific in-person location is left inapplicable
    // (excluded from the weighted score, not penalized) until real candidate-location data
    // exists to score it against honestly.
    public static Integer scoreLocation(String jobType, String jobLocation) {
        String combined = ((jobType == null ? "" : jobType) + " " + (jobLocation == null ? "" : jobLocation))
                .toLowerCase();
        if (combined.contains("remote") || combined.contains("work from home") || combined.contains("wfh")) {
            return 100;
        }
        return null;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
