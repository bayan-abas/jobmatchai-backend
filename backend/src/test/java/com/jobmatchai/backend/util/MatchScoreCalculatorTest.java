package com.jobmatchai.backend.util;

import com.jobmatchai.backend.util.MatchScoreCalculator.Component;
import com.jobmatchai.backend.util.MatchScoreCalculator.ComponentKey;
import com.jobmatchai.backend.util.MatchScoreCalculator.WeightedResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MatchScoreCalculatorTest {

    @Test
    void allComponentsPresent_usesFullWeighting() {
        // 25% field(80) + 25% skills(60) + 20% experience(70) + 15% education(90) +
        // 10% certification(50) + 5% location(100)
        // = 20 + 15 + 14 + 13.5 + 5 + 5 = 72.5 -> rounds to 73 (Math.round on 72.5 == 73)
        WeightedResult result = MatchScoreCalculator.compute(List.of(
                new Component(ComponentKey.FIELD_RELEVANCE, 80),
                new Component(ComponentKey.REQUIRED_SKILLS, 60),
                new Component(ComponentKey.EXPERIENCE, 70),
                new Component(ComponentKey.EDUCATION, 90),
                new Component(ComponentKey.CERTIFICATION, 50),
                new Component(ComponentKey.LOCATION, 100)
        ));

        assertThat(result.overallPercent()).isEqualTo(73);
    }

    @Test
    void missingComponents_areExcludedAndWeightIsRedistributed() {
        // Only field relevance (25%) and skills (25%) applicable, both scored 80 -> the missing
        // components' weight is proportionally redistributed, so overall is simply the average
        // of the two applicable components (80), not 80 * 0.5 = 40 as it would be if a null
        // score were incorrectly treated as a literal 0.
        WeightedResult result = MatchScoreCalculator.compute(List.of(
                new Component(ComponentKey.FIELD_RELEVANCE, 80),
                new Component(ComponentKey.REQUIRED_SKILLS, 80),
                new Component(ComponentKey.EXPERIENCE, null),
                new Component(ComponentKey.EDUCATION, null),
                new Component(ComponentKey.CERTIFICATION, null),
                new Component(ComponentKey.LOCATION, null)
        ));

        assertThat(result.overallPercent()).isEqualTo(80);
        assertThat(result.componentPercents().get(ComponentKey.EXPERIENCE)).isNull();
    }

    @Test
    void noApplicableComponents_returnsZeroRatherThanThrowing() {
        WeightedResult result = MatchScoreCalculator.compute(List.of(
                new Component(ComponentKey.FIELD_RELEVANCE, null),
                new Component(ComponentKey.REQUIRED_SKILLS, null)
        ));

        assertThat(result.overallPercent()).isZero();
    }

    @Test
    void skillsScore_allMandatoryMatched_isPerfect() {
        assertThat(MatchScoreCalculator.computeSkillsScore(3, 0, 0, 0)).isEqualTo(100);
    }

    @Test
    void skillsScore_missingPreferredCostsHalfOfMissingMandatory() {
        // 1 mandatory matched, 1 mandatory missing, 2 preferred (both present) -> only the
        // mandatory gap matters here.
        Integer mandatoryGapOnly = MatchScoreCalculator.computeSkillsScore(1, 1, 2, 0);
        // weightedTotal = 2 (mandatory) + 1 (2*0.5 preferred) = 3, matched = 1 + 1 = 2 -> 66.67 -> 67
        assertThat(mandatoryGapOnly).isEqualTo(67);

        // Same candidate but the gap is in a PREFERRED skill instead of a mandatory one should
        // score HIGHER - a smaller penalty for optional/preferred requirements.
        Integer preferredGapOnly = MatchScoreCalculator.computeSkillsScore(2, 0, 0, 1);
        // weightedTotal = 2 + 0.5 = 2.5, matched = 2 -> 80
        assertThat(preferredGapOnly).isEqualTo(80);

        assertThat(preferredGapOnly).isGreaterThan(mandatoryGapOnly);
    }

    @Test
    void skillsScore_noSkillsListed_isNotApplicable() {
        assertThat(MatchScoreCalculator.computeSkillsScore(0, 0, 0, 0)).isNull();
    }

    @Test
    void overallPercent_isClampedTo0To100() {
        WeightedResult result = MatchScoreCalculator.compute(List.of(
                new Component(ComponentKey.FIELD_RELEVANCE, 100),
                new Component(ComponentKey.REQUIRED_SKILLS, 100)
        ));

        assertThat(result.overallPercent()).isBetween(0, 100);
    }

    // ---- rule-table functions: same classification always produces the same number ----

    @Test
    void fieldRelevance_sameLabelAlwaysProducesSameNumber() {
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_role")).isEqualTo(95);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_specialization")).isEqualTo(80);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_broad_field")).isEqualTo(55);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("SAME_ROLE")).isEqualTo(95); // case-insensitive
        assertThat(MatchScoreCalculator.scoreFieldRelevance("general_vocational_role")).isEqualTo(85);
    }

    @Test
    void experienceScore_meetingOrExceedingRequiredLevel_isPerfect() {
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "senior")).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "entry")).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreExperience("mid_level", "mid")).isEqualTo(100);
    }

    @Test
    void experienceScore_shortfallCostsFixedPenaltyPerRank() {
        // entry_level (rank 1) vs required senior (rank 3): shortfall 2 * 40 = 80 -> 20.
        assertThat(MatchScoreCalculator.scoreExperience("entry_level", "senior")).isEqualTo(20);
        // none (rank 0) vs required entry (rank 1): shortfall 1 * 40 = 40 -> 60.
        assertThat(MatchScoreCalculator.scoreExperience("none", "entry")).isEqualTo(60);
        // none (rank 0) vs required senior (rank 3): shortfall 3 * 40 = 120 -> clamped to 0.
        assertThat(MatchScoreCalculator.scoreExperience("none", "senior")).isEqualTo(0);
    }

    @Test
    void educationScore_relevantDegreeSatisfiesEitherRequirement() {
        assertThat(MatchScoreCalculator.scoreEducation("relevant_degree", "any_degree")).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreEducation("relevant_degree", "relevant_degree")).isEqualTo(100);
    }

    @Test
    void educationScore_noEvidenceIsPenalizedMoreForRelevantDegreeThanAnyDegree() {
        int anyDegreeGap = MatchScoreCalculator.scoreEducation("none", "any_degree");   // shortfall 1 * 45 = 55
        int relevantDegreeGap = MatchScoreCalculator.scoreEducation("none", "relevant_degree"); // shortfall 2 * 45 -> 10
        assertThat(anyDegreeGap).isEqualTo(55);
        assertThat(relevantDegreeGap).isEqualTo(10);
        assertThat(anyDegreeGap).isGreaterThan(relevantDegreeGap);
    }

    @Test
    void certificationScore_specificLicense_sameSpecificRole_requiresTheActualLicense() {
        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "licensed", "specific_license", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "in_progress", "specific_license", true)).isEqualTo(55);
        // A strong general certification does NOT substitute for a missing specific license -
        // only the license evidence is consulted for this requirement level.
        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "none", "specific_license", true)).isEqualTo(15);
    }

    @Test
    void certificationScore_specificLicense_differentSpecificRole_discountsAnUnverifiedLicense() {
        // Found via live verification: a doctor's "licensed" flag doesn't prove they hold THIS
        // job's specific license (e.g. nursing) when the job is only same_broad_field - it must
        // score well below the same_specificRole=true case above, not the full 100.
        int sameRole = MatchScoreCalculator.scoreCertification("field_relevant", "licensed", "specific_license", true);
        int differentRole = MatchScoreCalculator.scoreCertification("field_relevant", "licensed", "specific_license", false);
        assertThat(differentRole).isEqualTo(40);
        assertThat(differentRole).isLessThan(sameRole);
    }

    @Test
    void certificationScore_generalCert_acceptsEitherACertificationOrALicense() {
        assertThat(MatchScoreCalculator.scoreCertification("general", "none", "general_cert", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreCertification("none", "licensed", "general_cert", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreCertification("none", "none", "general_cert", true)).isEqualTo(50);
    }

    @Test
    void locationScore_remotePostingIsTriviallyCompatible() {
        assertThat(MatchScoreCalculator.scoreLocation("Remote", "Anywhere")).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreLocation("Full-time", "Work From Home")).isEqualTo(100);
    }

    @Test
    void locationScore_specificOnsiteLocation_isNotApplicableWithoutCandidateLocationData() {
        assertThat(MatchScoreCalculator.scoreLocation("Full-time", "Tel Aviv")).isNull();
    }
}
