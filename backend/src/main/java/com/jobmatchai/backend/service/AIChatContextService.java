package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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

    public record ChatContext(String mode, String contextBlock) {}

    public ChatContext buildContext(String email, String language) {
        if (email == null || email.isBlank()) {
            return new ChatContext("anonymous", "");
        }

        User user = userRepository.findByEmail(email.trim());
        if (user == null) {
            return new ChatContext("anonymous", "");
        }

        if ("company".equalsIgnoreCase(user.getRole())) {
            return new ChatContext("company", buildCompanyContext(email.trim(), language));
        }

        return new ChatContext("candidate", buildCandidateContext(email.trim(), language));
    }

    private String buildCandidateContext(String email, String language) {
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

        sb.append(buildJobsWithMatchBlock(cappedJobs, matchByJobId));

        return sb.toString();
    }

    private String buildCompanyContext(String email, String language) {
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
            return sb.toString();
        }

        List<Long> jobIds = companyJobs.stream().map(job -> job.getId()).toList();
        List<Application> allApplications = applicationRepository.findByJobIdIn(jobIds);

        Map<Long, List<Application>> applicationsByJobId = allApplications.stream()
                .collect(Collectors.groupingBy(app -> app.getJobId()));

        // Dedupe CVAnalysis lookups across jobs — a candidate may have applied to multiple of this company's jobs.
        Map<String, CVAnalysis> analysisByEmail = new HashMap<>();

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
                    CVAnalysis candidateAnalysis = analysisByEmail.computeIfAbsent(
                            app.getCandidateEmail(),
                            candidateEmail -> cvAnalysisRepository.findByUserEmail(candidateEmail).orElse(null)
                    );

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

                        JobMatchService.MatchScoresResult result = jobMatchService.getMatchScores(
                                app.getCandidateEmail(), List.of(job), language);

                        if (result.hasAnalysis() && !result.matches().isEmpty()) {
                            Map<String, Object> match = result.matches().get(0);
                            sb.append("    Match percent for this job: ").append(match.get("matchPercent")).append("\n")
                                    .append("    Match reason: ").append(match.get("matchReason")).append("\n")
                                    .append("    Matched skills for this job: ").append(joinList(match.get("matchedSkills"))).append("\n")
                                    .append("    Missing skills for this job: ").append(joinList(match.get("missingSkills"))).append("\n");
                        } else {
                            sb.append("    Match percent for this job: not yet computed\n");
                        }
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        return sb.toString();
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
Score level: %s
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
                nullToNA(analysis.getScoreLevel()),
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

    private String buildJobsWithMatchBlock(List<Job> jobs, Map<Long, Map<String, Object>> matchByJobId) {
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
            if (match != null) {
                sb.append("Match percent for this candidate: ").append(match.get("matchPercent")).append("\n")
                        .append("Match reason: ").append(match.get("matchReason")).append("\n")
                        .append("Matched skills: ").append(joinList(match.get("matchedSkills"))).append("\n")
                        .append("Missing skills: ").append(joinList(match.get("missingSkills"))).append("\n");
            } else {
                sb.append("Match percent for this candidate: not yet computed\n");
            }
        }
        return sb.toString();
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
