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

        Integer mandatoryGapOnly = MatchScoreCalculator.computeSkillsScore(1, 1, 2, 0);

        assertThat(mandatoryGapOnly).isEqualTo(67);

        Integer preferredGapOnly = MatchScoreCalculator.computeSkillsScore(2, 0, 0, 1);

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

    @Test
    void fieldRelevance_sameLabelAlwaysProducesSameNumber() {
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_role")).isEqualTo(95);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_specialization")).isEqualTo(80);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("same_broad_field")).isEqualTo(55);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("SAME_ROLE")).isEqualTo(95);
        assertThat(MatchScoreCalculator.scoreFieldRelevance("general_vocational_role")).isEqualTo(25);
    }

    @Test
    void experienceScore_meetingOrExceedingRequiredLevel_isPerfect() {
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "senior", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "entry", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreExperience("mid_level", "mid", true)).isEqualTo(100);
    }

    @Test
    void experienceScore_shortfallCostsFixedPenaltyPerRank() {

        assertThat(MatchScoreCalculator.scoreExperience("entry_level", "senior", true)).isEqualTo(20);

        assertThat(MatchScoreCalculator.scoreExperience("none", "entry", true)).isEqualTo(60);

        assertThat(MatchScoreCalculator.scoreExperience("none", "senior", true)).isEqualTo(0);
    }

    @Test
    void experienceScore_discountsOneRankWhenNotCandidatesOwnSpecificRole() {

        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "senior", false)).isEqualTo(60);
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "mid", false)).isEqualTo(100);

        assertThat(MatchScoreCalculator.scoreExperience("entry_level", "entry", false)).isEqualTo(60);
    }

    @Test
    void experienceScore_seniorCandidateMissingSpecificType_isBlendedNotZeroedNorIgnored() {

        assertThat(MatchScoreCalculator.scoreExperience(
                "senior_level", "mid", true, true, false)).isEqualTo(50);
    }

    @Test
    void experienceScore_specificTypePresent_isFullCreditJustLikeAmountOnly() {

        assertThat(MatchScoreCalculator.scoreExperience(
                "senior_level", "mid", true, true, true)).isEqualTo(100);
    }

    @Test
    void experienceScore_noSpecificTypeRequired_matchesThreeArgOverloadExactly() {

        assertThat(MatchScoreCalculator.scoreExperience("mid_level", "senior", true, false, false))
                .isEqualTo(MatchScoreCalculator.scoreExperience("mid_level", "senior", true));
        assertThat(MatchScoreCalculator.scoreExperience("senior_level", "entry", true, false, true))
                .isEqualTo(MatchScoreCalculator.scoreExperience("senior_level", "entry", true));
    }

    @Test
    void experienceScore_typeGapCompoundsWithAnAmountShortfall() {

        assertThat(MatchScoreCalculator.scoreExperience(
                "entry_level", "senior", true, true, false)).isEqualTo(10);
    }

    @Test
    void educationScore_relevantDegreeSatisfiesEitherRequirement() {
        assertThat(MatchScoreCalculator.scoreEducation("relevant_degree", "any_degree")).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreEducation("relevant_degree", "relevant_degree")).isEqualTo(100);
    }

    @Test
    void educationScore_noEvidenceIsPenalizedMoreForRelevantDegreeThanAnyDegree() {
        int anyDegreeGap = MatchScoreCalculator.scoreEducation("none", "any_degree");
        int relevantDegreeGap = MatchScoreCalculator.scoreEducation("none", "relevant_degree");
        assertThat(anyDegreeGap).isEqualTo(55);
        assertThat(relevantDegreeGap).isEqualTo(10);
        assertThat(anyDegreeGap).isGreaterThan(relevantDegreeGap);
    }

    @Test
    void certificationScore_specificLicense_sameSpecificRole_requiresTheActualLicense() {
        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "licensed", "specific_license", true)).isEqualTo(100);
        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "in_progress", "specific_license", true)).isEqualTo(55);

        assertThat(MatchScoreCalculator.scoreCertification("field_relevant", "none", "specific_license", true)).isEqualTo(15);
    }

    @Test
    void certificationScore_specificLicense_differentSpecificRole_discountsAnUnverifiedLicense() {

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
