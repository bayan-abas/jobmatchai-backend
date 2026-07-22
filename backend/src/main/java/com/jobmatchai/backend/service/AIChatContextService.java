package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.JobMatchScore;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AIChatContextService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobMatchService jobMatchService;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    public record ChatContext(String mode, String contextBlock, List<CanonicalFact> facts) {}

    // The same canonical per-(job, candidate) match facts the contextBlock text is built FROM,
    // kept as typed data alongside the stringified prompt so ChatConsistencyValidator can check an
    // AI-generated reply against the exact numbers/skill lists it was given, instead of the
    // validator having to re-parse contextBlock's free-text formatting (which is prompt-tuning
    // surface, not a stable data contract) or re-query the database itself. candidateName is null
    // in candidate mode - the chat's "you" is unambiguous there - and non-null in company mode,
    // where several candidates can be discussed for the same job in one reply.
    public record CanonicalFact(
            Long jobId,
            String jobTitle,
            String candidateEmail,
            String candidateName,
            Integer matchPercent,
            Boolean fieldRelated,
            List<String> matchedSkills,
            List<String> missingSkills,
            Integer experienceMatchPercent,
            Integer educationMatchPercent,
            Integer certificationMatchPercent,
            Integer fieldRelevancePercent,
            String matchReason
    ) {}

    private record ModeContext(String contextBlock, List<CanonicalFact> facts) {}

    public ChatContext buildContext(String email, String language) {
        if (email == null || email.isBlank()) {
            return new ChatContext("anonymous", "", List.of());
        }

        User user = userRepository.findByEmail(email.trim());
        if (user == null) {
            return new ChatContext("anonymous", "", List.of());
        }

        if ("company".equalsIgnoreCase(user.getRole())) {
            ModeContext company = buildCompanyContext(email.trim(), language);
            return new ChatContext("company", company.contextBlock(), company.facts());
        }

        ModeContext candidate = buildCandidateContext(email.trim(), language);
        return new ChatContext("candidate", candidate.contextBlock(), candidate.facts());
    }

    private ModeContext buildCandidateContext(String email, String language) {
        CVAnalysis analysis = cvAnalysisRepository.findByUserEmail(email).orElse(null);
        List<Application> applications = applicationRepository.findByCandidateEmail(email);

        List<Job> allJobs = jobRepository.findAll();
        List<Job> cappedJobs = allJobs.size() > 50 ? allJobs.subList(0, 50) : allJobs;

        Map<Long, Map<String, Object>> matchByJobId = new HashMap<>();
        if (analysis != null && !cappedJobs.isEmpty()) {
            JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(email, cappedJobs, language);
            for (Map<String, Object> match : result.matches()) {
                Object jobIdObj = match.get("jobId");
                if (jobIdObj instanceof Number number) {
                    matchByJobId.put(number.longValue(), match);
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        sb.append(pickByLanguage(language,
                "=== CANDIDATE CV ANALYSIS ===\n",
                "=== تحليل السيرة الذاتية للمرشح ===\n",
                "=== ניתוח קורות החיים של המועמד ===\n"));
        sb.append(buildCandidateProfileBlock(analysis));
        sb.append("\n");

        sb.append(pickByLanguage(language,
                "=== CANDIDATE'S APPLICATIONS ===\n",
                "=== طلبات التوظيف الخاصة بالمرشح ===\n",
                "=== הבקשות של המועמד ===\n"));
        sb.append(buildApplicationsBlock(applications, language));
        sb.append("\n");

        sb.append(pickByLanguage(language,
                "=== AVAILABLE JOBS (with this candidate's match data where available) ===\n",
                "=== الوظائف المتاحة (مع بيانات التطابق لهذا المرشح عند توفرها) ===\n",
                "=== משרות זמינות (עם נתוני התאמה של מועמד זה כאשר זמינים) ===\n"));

        if (analysis == null) {
            sb.append(pickByLanguage(language,
                    "No CV analysis on file yet — match percentages are not available until the candidate analyzes their CV.\n",
                    "لا يوجد تحليل للسيرة الذاتية بعد — نسب التطابق غير متاحة حتى يقوم المرشح بتحليل سيرته الذاتية.\n",
                    "אין עדיין ניתוח קורות חיים — אחוזי ההתאמה אינם זמינים עד שהמועמד ינתח את קורות החיים שלו.\n"));
        }

        List<CanonicalFact> facts = new ArrayList<>();
        sb.append(buildJobsWithMatchBlock(cappedJobs, matchByJobId, email, facts));

        return new ModeContext(sb.toString(), facts);
    }

    private ModeContext buildCompanyContext(String email, String language) {
        List<Job> companyJobs = jobRepository.findByCompanyEmail(email);
        if (companyJobs.size() > 20) {
            companyJobs = companyJobs.subList(0, 20);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(pickByLanguage(language,
                "=== COMPANY'S JOB POSTINGS ===\n",
                "=== الوظائف المعلنة من الشركة ===\n",
                "=== משרות החברה ===\n"));

        if (companyJobs.isEmpty()) {
            sb.append(pickByLanguage(language,
                    "This company currently has no job postings.\n",
                    "لا توجد وظائف معلنة حاليًا لهذه الشركة.\n",
                    "לחברה זו אין כרגע משרות פתוחות.\n"));
            return new ModeContext(sb.toString(), List.of());
        }

        List<Long> jobIds = companyJobs.stream().map(job -> job.getId()).toList();
        List<Application> allApplications = applicationRepository.findByJobIdIn(jobIds);

        Map<Long, List<Application>> applicationsByJobId = allApplications.stream()
                .collect(Collectors.groupingBy(app -> app.getJobId()));

        // Batch-fetch CVAnalysis and cached JobMatchScore rows for every applicant up front,
        // instead of one query per applicant (and, for match scores, previously one query PLUS
        // a possible synchronous OpenAI call per applicant-per-job pair). This context is only
        // ever read from cache - it must never trigger fresh AI scoring, which belongs solely
        // to the candidate-facing "Job Matches" and company-facing "AI Summary" features.
        List<String> candidateEmails = allApplications.stream().map(Application::getCandidateEmail).distinct().toList();

        Map<String, CVAnalysis> analysisByEmail = new HashMap<>();
        if (!candidateEmails.isEmpty()) {
            for (CVAnalysis analysis : cvAnalysisRepository.findByUserEmailIn(candidateEmails)) {
                analysisByEmail.put(analysis.getUserEmail(), analysis);
            }
        }

        Map<String, JobMatchScore> matchScoreByKey = new HashMap<>();
        if (!candidateEmails.isEmpty()) {
            for (JobMatchScore score : jobMatchScoreRepository.findByCandidateEmailInAndJobIdIn(candidateEmails, jobIds)) {
                matchScoreByKey.put(score.getCandidateEmail() + "::" + score.getJobId(), score);
            }
        }

        List<CanonicalFact> facts = new ArrayList<>();

        for (Job job : companyJobs) {
            sb.append("---\n")
                    .append("jobId: ").append(job.getId()).append("\n")
                    .append("Title: ").append(nullToNA(job.getTitle())).append("\n")
                    .append("Type: ").append(nullToNA(job.getType())).append("\n")
                    .append("Location: ").append(nullToNA(job.getLocation())).append("\n")
                    .append("Salary: ").append(nullToNA(job.getSalary())).append("\n")
                    .append("Required skills: ").append(nullToNA(job.getSkills())).append("\n")
                    .append("Requirements: ").append(nullToNA(job.getRequirements())).append("\n");

            List<Application> applicants = applicationsByJobId.getOrDefault(job.getId(), List.of());
            if (applicants.size() > 20) {
                applicants = applicants.subList(0, 20);
            }

            sb.append("Applicants for this job:\n");

            if (applicants.isEmpty()) {
                sb.append(pickByLanguage(language,
                        "No applications yet for this job.\n",
                        "لا توجد طلبات توظيف لهذه الوظيفة بعد.\n",
                        "אין עדיין בקשות למשרה זו.\n"));
            } else {
                for (Application app : applicants) {
                    CVAnalysis candidateAnalysis = analysisByEmail.get(app.getCandidateEmail());

                    sb.append("  * Candidate: ").append(nullToNA(app.getCandidateName()))
                            .append(" (").append(nullToNA(app.getCandidateEmail())).append(")\n")
                            .append("    Application status: ").append(nullToNA(app.getStatus()))
                            .append(" | applied: ").append(nullToNA(app.getAppliedDate())).append("\n");

                    if (candidateAnalysis == null) {
                        sb.append("    No CV analysis available for this candidate.\n");
                    } else {
                        sb.append("    Field: ").append(nullToNA(candidateAnalysis.getCandidateField())).append("\n")
                                .append("    Skills: ").append(nullToNA(candidateAnalysis.getSkills())).append("\n")
                                .append("    Summary: ").append(nullToNA(candidateAnalysis.getSummary())).append("\n")
                                .append("    Strengths: ").append(nullToNA(candidateAnalysis.getStrengths())).append("\n")
                                .append("    Missing skills (general): ").append(nullToNA(candidateAnalysis.getMissingSkills())).append("\n")
                                .append("    Overall CV score: ").append(nullToNA(candidateAnalysis.getOverallScore())).append("\n");

                        JobMatchScore matchScore = matchScoreByKey.get(app.getCandidateEmail() + "::" + job.getId());

                        if (matchScore != null && matchScore.getMatchPercent() != null) {
                            sb.append("    Match percent for this job: ").append(matchScore.getMatchPercent()).append("\n")
                                    .append("    Field-related: ").append(nullToNA(matchScore.getFieldRelated())).append("\n")
                                    .append("    Match reason: ").append(nullToNA(matchScore.getMatchReason())).append("\n")
                                    .append("    Matched skills for this job: ").append(nullToNA(matchScore.getMatchedSkills())).append("\n")
                                    .append("    Missing skills for this job: ").append(nullToNA(matchScore.getMissingSkills())).append("\n")
                                    .append("    Experience match: ").append(nullToNA(matchScore.getExperienceMatchPercent())).append("\n")
                                    .append("    Education match: ").append(nullToNA(matchScore.getEducationMatchPercent())).append("\n")
                                    .append("    Certification match: ").append(nullToNA(matchScore.getCertificationMatchPercent())).append("\n")
                                    .append("    Field relevance: ").append(nullToNA(matchScore.getFieldRelevancePercent())).append("\n");

                            facts.add(new CanonicalFact(
                                    job.getId(), job.getTitle(), app.getCandidateEmail(), app.getCandidateName(),
                                    matchScore.getMatchPercent(), matchScore.getFieldRelated(),
                                    splitSkillsString(matchScore.getMatchedSkills()), splitSkillsString(matchScore.getMissingSkills()),
                                    matchScore.getExperienceMatchPercent(), matchScore.getEducationMatchPercent(),
                                    matchScore.getCertificationMatchPercent(), matchScore.getFieldRelevancePercent(),
                                    matchScore.getMatchReason()));
                        } else {
                            sb.append("    Match percent for this job: not yet computed\n");
                        }
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        return new ModeContext(sb.toString(), facts);
    }

    private List<String> splitSkillsString(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\|"));
    }

    private String buildCandidateProfileBlock(CVAnalysis analysis) {
        if (analysis == null) {
            return "No CV analysis on file for this candidate yet.\n";
        }

        return """
Field: %s
Skills: %s
Summary: %s
Strengths: %s
Missing skills (general): %s
Recommended roles: %s
Overall CV score: %s
Score rationale: %s
CV quality notes: %s
""".formatted(
                nullToNA(analysis.getCandidateField()),
                nullToNA(analysis.getSkills()),
                nullToNA(analysis.getSummary()),
                nullToNA(analysis.getStrengths()),
                nullToNA(analysis.getMissingSkills()),
                nullToNA(analysis.getRecommendedRoles()),
                nullToNA(analysis.getOverallScore()),
                nullToNA(analysis.getEvaluationReason()),
                nullToNA(analysis.getMissingInformation())
        );
    }

    private String buildApplicationsBlock(List<Application> applications, String language) {
        if (applications == null || applications.isEmpty()) {
            return pickByLanguage(language,
                    "No applications yet.\n",
                    "لا توجد طلبات توظيف بعد.\n",
                    "אין עדיין בקשות.\n");
        }

        StringBuilder sb = new StringBuilder();
        for (Application app : applications) {
            sb.append("- ")
                    .append(nullToNA(app.getJobTitle())).append(" @ ").append(nullToNA(app.getCompanyName()))
                    .append(" | jobId: ").append(app.getJobId())
                    .append(" | status: ").append(nullToNA(app.getStatus()))
                    .append(" | applied: ").append(nullToNA(app.getAppliedDate()))
                    .append("\n");
        }
        return sb.toString();
    }

    // email/facts: email identifies the (single, implicit) candidate these matches belong to -
    // every fact this appends is for THAT candidate, one per job - and facts is the typed,
    // structured twin of the text this method also writes into sb (see CanonicalFact's own
    // comment for why both exist).
    private String buildJobsWithMatchBlock(
            List<Job> jobs, Map<Long, Map<String, Object>> matchByJobId, String email, List<CanonicalFact> facts) {
        if (jobs == null || jobs.isEmpty()) {
            return "No jobs currently available in the system.\n";
        }

        StringBuilder sb = new StringBuilder();
        for (Job job : jobs) {
            String description = job.getDescription();
            if (description != null && description.length() > 500) {
                description = description.substring(0, 500);
            }

            sb.append("---\n")
                    .append("jobId: ").append(job.getId()).append("\n")
                    .append("Title: ").append(nullToNA(job.getTitle())).append("\n")
                    .append("Company: ").append(nullToNA(job.getCompanyName())).append("\n")
                    .append("Type: ").append(nullToNA(job.getType())).append("\n")
                    .append("Location: ").append(nullToNA(job.getLocation())).append("\n")
                    .append("Salary: ").append(nullToNA(job.getSalary())).append("\n")
                    .append("Required skills: ").append(nullToNA(job.getSkills())).append("\n")
                    .append("Requirements: ").append(nullToNA(job.getRequirements())).append("\n")
                    .append("Description: ").append(nullToNA(description)).append("\n");

            Map<String, Object> match = matchByJobId.get(job.getId());
            if (match != null && match.get("matchPercent") != null) {
                sb.append("Match percent for this candidate: ").append(match.get("matchPercent")).append("\n")
                        .append("Field-related: ").append(match.get("fieldRelated")).append("\n")
                        .append("Match reason: ").append(match.get("matchReason")).append("\n")
                        .append("Matched skills: ").append(joinList(match.get("matchedSkills"))).append("\n")
                        .append("Missing skills: ").append(joinList(match.get("missingSkills"))).append("\n")
                        .append("Experience match: ").append(match.get("experienceMatchPercent")).append("\n")
                        .append("Education match: ").append(match.get("educationMatchPercent")).append("\n")
                        .append("Certification match: ").append(match.get("certificationMatchPercent")).append("\n")
                        .append("Field relevance: ").append(match.get("fieldRelevancePercent")).append("\n");

                facts.add(new CanonicalFact(
                        job.getId(), job.getTitle(), email, null,
                        asInteger(match.get("matchPercent")), asBoolean(match.get("fieldRelated")),
                        asStringList(match.get("matchedSkills")), asStringList(match.get("missingSkills")),
                        asInteger(match.get("experienceMatchPercent")), asInteger(match.get("educationMatchPercent")),
                        asInteger(match.get("certificationMatchPercent")), asInteger(match.get("fieldRelevancePercent")),
                        asString(match.get("matchReason"))));
            } else {
                sb.append("Match percent for this candidate: not yet computed\n");
            }
        }
        return sb.toString();
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private Boolean asBoolean(Object value) {
        return value instanceof Boolean bool ? bool : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> asStringList(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private String joinList(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "none" : list.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        return "none";
    }

    private String nullToNA(Object value) {
        if (value == null) {
            return "N/A";
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "N/A" : text;
    }

    private String pickByLanguage(String language, String en, String ar, String he) {
        return switch (language == null ? "en" : language) {
            case "ar" -> ar;
            case "he" -> he;
            default -> en;
        };
    }
}
