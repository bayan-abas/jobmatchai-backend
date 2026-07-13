package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class OpenAICVAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(OpenAICVAnalysisService.class);

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    // computeJobMatches is called far more often, and at far higher volume per user action
    // (one call per 10-job chunk, several chunks per page load), than the other single-shot
    // calls in this class (analyzeCV, computeJobMatchDetail, computeCandidateSummary) - it's
    // a bounded classification/scoring task (score N jobs against one profile, structured
    // JSON out) rather than open-ended reasoning, so it doesn't need the flagship model. Using
    // a smaller, faster model just for this call is what actually cuts the wall-clock wait for
    // match percentages to appear, instead of only tuning chunk size/concurrency around the
    // same slow model. Defaults to a distinct model but falls back to the main model if unset.
    @Value("${openai.match-model:${openai.model}}")
    private String matchModel;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    @Qualifier("openAIRestClient")
    private RestClient restClient;

    public String analyzeCV(String cvText, String language) {
        try {
            String safeCvText = cvText;

            if (safeCvText != null && safeCvText.length() > 12000) {
                safeCvText = safeCvText.substring(0, 12000);
            }

            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> """
LANGUAGE RULE — THIS IS MANDATORY:
Every user-facing text value in your JSON response (professionTitle, summary, strengths, cvQualityIssues, recommendedRoles, missingSkills, estimatedYearsExperience, scoreRationale) MUST be written entirely in Arabic.
Do not use English words in those fields. Arabic only, no exceptions.
Exception: classification fields such as "candidateField", "careerStage", "professionalExperienceEvidence", "portfolioEvidence", "educationEvidence", "certificationsEvidence", "leadershipEvidence", "communicationEvidence", and "achievementEvidence" must use the exact English enum values requested below.
""";
                case "he" -> """
LANGUAGE RULE — THIS IS MANDATORY:
Every user-facing text value in your JSON response (professionTitle, summary, strengths, cvQualityIssues, recommendedRoles, missingSkills, estimatedYearsExperience, scoreRationale) MUST be written entirely in Hebrew.
Do not use English words in those fields. Hebrew only, no exceptions.
Exception: classification fields such as "candidateField", "careerStage", "professionalExperienceEvidence", "portfolioEvidence", "educationEvidence", "certificationsEvidence", "leadershipEvidence", "communicationEvidence", and "achievementEvidence" must use the exact English enum values requested below.
""";
                default -> """
LANGUAGE RULE: Write all user-facing text values in English. Classification fields must use the exact English enum values requested below.
""";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No titles. No explanations outside the JSON.
""" + languageInstruction + """

You are a senior career advisor at JobMatchAI. Read the CV like a human career coach, not like a keyword scanner.
The CV may be written in Hebrew, Arabic, English, or mixed languages. Understand the content semantically before judging it.
If the CV is in Hebrew or Arabic, mentally translate it first, but write the response in the requested response language.

Your first task is to identify the candidate's profession, industry, seniority, and career stage from the actual CV text.
Do NOT assume the candidate is a software developer. Do NOT use software-engineering standards unless the CV is actually a software CV.
Evaluate the candidate by the standards of their own profession: construction, accounting, teaching, nursing, cooking, cleaning, sales, support, administration, trades, management, or any other field.

Core evaluation philosophy:
- A candidate with many years of relevant work experience, several companies, role progression, responsibilities, leadership, certifications, or education should receive meaningful credit even if the CV does not list many technical keywords.
- Work history is evidence. Job titles are evidence. Companies are evidence. Dates and duration are evidence. Responsibilities are evidence. Achievements are evidence.
- For non-technical careers, value reliability, responsibility, customer service, operations, safety, organization, leadership, communication, and field-specific practical expertise.
- Missing technologies must never lower the score for non-software candidates.
- The score should represent the overall strength of the candidate profile and CV presentation, not only ATS keyword density.
- Career changers: a candidate's most recent PAID job is not automatically their field. When a degree (especially one in progress), coursework, technical skills, or stated career interests clearly point toward a different field than the candidate's work history, that is a career transition (careerStage "career_transition") - identify candidateField and professionTitle by what the candidate is now qualified for and actively pursuing (their education/skills/stated direction), not just their most recent job title. Example: someone working as a customer support agent while completing a Computer Science or Information Systems degree with real programming coursework (e.g. Java, Python, SQL, web development) is a software career-changer, not a permanent customer-support professional - their limited paid experience in the new field is a normal, expected part of transitioning, not a reason to discount the field itself.

ABSOLUTE RULES:
- Base every claim on something written in the CV. You may interpret professional meaning from job titles, dates, responsibilities, and industry context, but do not invent facts.
- If employment history exists, never say there is no experience. Recognize years, companies, titles, and progression even when written in Hebrew or Arabic.
- If exact years are unclear, estimate career stage from dates, number of roles, seniority words, and responsibility level.
- Write as a real career advisor speaking directly to the candidate using "you" and "your".
- Avoid generic filler. Make the explanation feel personal and grounded in the CV.

Return exactly this JSON structure:
{
  "candidateField": "",
  "professionTitle": "",
  "careerStage": "",
  "estimatedYearsExperience": "",
  "skills": "",
  "technicalSkills": "",
  "softSkills": "",
  "languages": "",
  "previousJobTitles": "",
  "summary": "",
  "strengths": "",
  "missingSkills": "",
  "recommendedRoles": "",
  "professionalExperienceEvidence": "",
  "experienceDepthScore": 0,
  "portfolioEvidence": "",
  "portfolioDetailScore": 0,
  "educationEvidence": "",
  "certificationsEvidence": "",
  "licensesEvidence": "",
  "leadershipEvidence": "",
  "communicationEvidence": "",
  "achievementEvidence": "",
  "cvQualityIssues": "",
  "overallProfileScore": 0,
  "scoreRationale": ""
}

Field instructions:

1. candidateField: Identify the field the candidate is qualified for and pursuing NOW, per the career-changer guidance above - not necessarily their most recent paid job. Use exactly one English value:
   "software", "construction", "healthcare", "education", "business", "finance", "legal", "trades", "hospitality", "manufacturing", "sales", "customer_support", "administration", "operations", "cleaning", "other".

2. professionTitle: The best specific profession/title for this candidate, such as "Construction Manager", "Accountant", "Teacher", "Nurse", "Chef", "Cleaner", "Sales Representative", "Administrative Employee", "Software Developer".

3. careerStage: exactly one of "entry", "experienced", "senior", "managerial", "career_transition".

4. estimatedYearsExperience: Use the CV dates/history. Return a short text such as "0-1", "2-4", "5-7", "8+", or "unclear but experienced".

5. skills: A comma-separated list of skills and professional capabilities explicitly supported by the CV. Include field-specific practical skills and soft/professional skills when evidenced by roles or responsibilities. For example: site supervision, budgeting, lesson planning, patient care, food preparation, customer service, office administration, sales negotiation, team leadership. List named technologies, programming languages, tools, and platforms individually and by their exact name as written in the CV (e.g. "Java", "Python", "SQL", "HTML", "CSS", "JavaScript", "Node.js", "SAP S/4HANA") - never collapse them into a vague summary phrase like "software fundamentals" or "programming skills", since job matching later needs the exact names to compare against job requirements.

6. summary: Write 4-5 warm, specific sentences directly to the candidate. Explain the profession you identified, career stage, what makes the profile valuable, and the main opportunity to improve. Mention concrete evidence from the CV. If the candidate's education, certifications, or experience genuinely support more than one distinct professional field (e.g. a career changer with real credentials/experience in both an old and a new field, or someone dual-qualified in two professions), explicitly name every field they are genuinely qualified for, not just the single primary one - this profile is read by a job-matching system that needs to recognize all of it, not just the headline field.

7. strengths: Write 5-7 sentences of natural prose, not bullets. Cover what the candidate is doing well, what makes them valuable, strongest qualifications, responsibilities, career progression, education/certifications, communication/leadership, and practical field experience where present. Tie each point to the candidate's actual CV. Make sure education, certifications, and languages are each mentioned explicitly if the CV shows them, even briefly - do not let them get absorbed into vague summary phrases.

5b. technicalSkills: A comma-separated subset of "skills" above containing only hard/technical/domain-specific practical skills, named technologies, tools, and platforms (e.g. "Java", "Python", "patient charting systems", "AutoCAD", "SAP S/4HANA", "surgical suturing"). Exclude soft skills. "none" if the CV shows none.

5c. softSkills: A comma-separated list of interpersonal/professional soft skills clearly evidenced by roles or responsibilities (e.g. "communication", "leadership", "teamwork", "customer service", "time management"). "none" if the CV shows none.

5d. languages: A comma-separated list of spoken/written languages the candidate knows, with proficiency if stated (e.g. "English (fluent), Arabic (native), Hebrew (intermediate)"). "none" if not mentioned in the CV.

5e. previousJobTitles: A comma-separated list of the candidate's past job titles/roles, in the exact wording used in the CV, most recent first. "none" if the CV shows no job titles.

8. missingSkills: A comma-separated list of genuinely useful missing skills, certifications, or details for the detected profession only. Never suggest programming skills unless candidateField is "software". Do not include anything already present in the CV.

9. professionalExperienceEvidence: exactly one of:
   "none" - no work experience mentioned
   "entry_level" - limited or early work experience
   "mid_level" - clear relevant work experience, usually 2-7 years or multiple meaningful roles
   "senior_level" - 7+ years, senior duties, management, leadership, or broad professional history

10. experienceDepthScore: integer 0-5:
    0 no experience, 1 titles only, 2 titles plus companies/dates, 3 responsibilities described, 4 several roles with progression or broad responsibility, 5 strong responsibilities plus measurable impact/leadership.

11. portfolioEvidence: Evidence of work output relevant to the field. exactly one of:
    "none", "basic", "relevant", "strong".
    For non-software fields, work output means projects, sites, cases, students/classes, shifts/operations, customers served, administrative processes, menus/events, sales activity, managed teams, or other real professional output.

12. portfolioDetailScore: integer 0-5 using field-relevant work detail, not only software projects.

13. educationEvidence: exactly one of "none", "general", "relevant_degree".

14. certificationsEvidence: exactly one of "none", "general", "field_relevant". General professional certifications/courses/training, not a legal practice license.

14b. licensesEvidence: exactly one of "none", "in_progress", "licensed". Specifically about a formal LEGAL LICENSE or registration required to practice a regulated profession - medical license, nursing registration/license, bar admission (law), teaching license/certification, professional engineering (PE) license, pharmacy license, pilot license, licensed electrician/contractor, CPA license, etc. "licensed" only if the CV states the candidate holds (or historically held) such a license/registration/degree that grants it (e.g. "M.D.", "licensed physician", "Registered Nurse (RN)", "admitted to the bar", "board-certified"). "in_progress" if currently studying for or awaiting that license (e.g. in medical/nursing/law school, residency, internship). "none" if the CV shows no evidence of a practice license for a regulated profession, or the candidate's profession does not require one.

15. leadershipEvidence: exactly one of "none", "implicit", "clear", "strong".
    Give credit for management, supervision, training, responsibility for operations, leading teams, coordinating people, or senior roles.

16. communicationEvidence: exactly one of "none", "implicit", "clear", "strong".
    Give credit for client-facing roles, teaching, sales, support, administration, coordination, reporting, teamwork, languages, or stakeholder communication.

17. achievementEvidence: exactly one of "none", "general", "measurable".

18. cvQualityIssues: Write 3-5 practical coaching sentences. Explain what can be improved in the CV presentation and career positioning. Make suggestions relevant to the detected profession.

19. overallProfileScore: integer 0-100. This is your holistic career-advisor score for the candidate profile and CV quality.
    Scoring guide:
    - 0-20: almost no usable CV information or no professional direction.
    - 21-40: very early profile or sparse CV with little work evidence.
    - 41-60: some relevant experience/education but incomplete presentation.
    - 61-75: solid candidate with clear relevant experience and useful skills, but missing details or measurable achievements.
    - 76-88: strong candidate with meaningful experience, relevant skills, and a credible career path.
    - 89-100: excellent candidate with strong experience, clear achievements, leadership, certifications/education, and well-presented evidence.
    Important: A CV with many years of relevant professional experience and complete employment history should usually be at least 60 unless it is extremely unclear or unrelated.
    A senior/managerial CV with clear companies, titles, and responsibilities should usually be 70+ even without many keyword skills.

20. scoreRationale: Write 3-5 personal sentences explaining why the score is fair. Mention experience, profession, strongest qualifications, and what would raise the score. This must sound like a real career advisor, not a keyword checker.

CV Text:
""" + safeCvText;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return emptyAnalysisJson(pickByLanguage(language,
                        "I could not read the AI analysis clearly. Please try again.",
                        "تعذّر قراءة نتيجة التحليل بوضوح. يرجى المحاولة مرة أخرى.",
                        "לא ניתן היה לקרוא את תוצאת הניתוח בבירור. אנא נסה שוב."));
            }

            JsonNode json = objectMapper.readTree(result);

            // Build a set of existing skills (lowercase) for fast lookup
            String rawSkills = json.path("skills").asText("");
            Set<String> skillsSet = new HashSet<>();
            for (String s : rawSkills.split("[,;\\n]")) {
                String trimmed = s.trim().toLowerCase();
                if (!trimmed.isBlank()) {
                    skillsSet.add(trimmed);
                }
            }

            // Filter missingSkills: remove anything already present in skills or CV text
            String rawMissing = json.path("missingSkills").asText("");
            List<String> filteredMissing = new ArrayList<>();
            for (String s : rawMissing.split("[,;\\n]")) {
                String trimmed = s.trim();
                if (trimmed.isBlank()) continue;
                String lower = trimmed.toLowerCase();
                boolean alreadyPresent = false;
                for (String existing : skillsSet) {
                    if (existing.contains(lower) || lower.contains(existing)) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    filteredMissing.add(trimmed);
                }
            }
            String filteredMissingStr = String.join(", ", filteredMissing);

            int score = calculateRealisticScore(json);
            String scoreLevel = getScoreLevel(score, language);
            String evaluationReason = json.path("scoreRationale").asText("");
            if (evaluationReason.isBlank()) {
                evaluationReason = buildEvaluationReason(json, score, language);
            }

            ObjectNode finalJson = objectMapper.createObjectNode();

            finalJson.put("candidateField", json.path("candidateField").asText("other"));
            finalJson.put("professionTitle", json.path("professionTitle").asText(""));
            finalJson.put("skills", rawSkills);
            finalJson.put("technicalSkills", json.path("technicalSkills").asText(""));
            finalJson.put("softSkills", json.path("softSkills").asText(""));
            finalJson.put("languages", json.path("languages").asText(""));
            finalJson.put("previousJobTitles", json.path("previousJobTitles").asText(""));
            finalJson.put("summary", json.path("summary").asText(""));
            finalJson.put("strengths", json.path("strengths").asText(""));
            finalJson.put("missingSkills", filteredMissingStr);
            finalJson.put("recommendedRoles", json.path("recommendedRoles").asText(""));
            finalJson.put("overallScore", String.valueOf(score));
            finalJson.put("scoreLevel", scoreLevel);
            finalJson.put("evaluationReason", evaluationReason);
            finalJson.put("missingInformation", json.path("cvQualityIssues").asText(""));
            finalJson.put("educationEvidence", json.path("educationEvidence").asText(""));
            finalJson.put("certificationsEvidence", json.path("certificationsEvidence").asText(""));
            finalJson.put("licensesEvidence", json.path("licensesEvidence").asText(""));
            finalJson.put("yearsOfExperience", json.path("estimatedYearsExperience").asText(""));
            // professionalExperienceEvidence is already a fixed vocabulary (none/entry_level/
            // mid_level/senior_level) - reused verbatim as experienceLevel, the value
            // JobMatchService's deterministic experience-scoring formula compares against a
            // job's required seniority, instead of trying to parse a free-text years string.
            finalJson.put("experienceLevel", json.path("professionalExperienceEvidence").asText("none"));

            return objectMapper.writeValueAsString(finalJson);

        } catch (HttpClientErrorException e) {
            return emptyAnalysisJson(pickByLanguage(language,
                    "OpenAI API Error: " + e.getStatusCode(),
                    "خطأ في واجهة OpenAI: " + e.getStatusCode(),
                    "שגיאת API של OpenAI: " + e.getStatusCode()));
        } catch (Exception e) {
            return emptyAnalysisJson(pickByLanguage(language,
                    "Error analyzing CV: " + e.getMessage(),
                    "حدث خطأ أثناء تحليل السيرة الذاتية: " + e.getMessage(),
                    "אירעה שגיאה בניתוח קורות החיים: " + e.getMessage()));
        }
    }

    public String validateCV(String cvText, String language) {
        try {
            String safeCvText = cvText;

            if (safeCvText == null || safeCvText.isBlank()) {
                return invalidCVJson(pickByLanguage(language,
                        "The file does not contain readable text.",
                        "لا يحتوي الملف على نص قابل للقراءة.",
                        "הקובץ אינו מכיל טקסט קריא."), 0);
            }

            if (safeCvText.length() > 6000) {
                safeCvText = safeCvText.substring(0, 6000);
            }

            String reasonLanguageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write the \"reason\" field entirely in Arabic.";
                case "he" -> "Write the \"reason\" field entirely in Hebrew.";
                default -> "Write the \"reason\" field in English.";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No titles. No explanations.

You are a document classifier for a recruitment system.
""" + reasonLanguageInstruction + "\n" + """

Decide if the uploaded document is a real candidate CV / resume.

Accept as CV/resume if it contains several of these:
- personal/contact details
- education
- work experience
- projects
- skills
- languages
- summary/profile
- certifications
- employment-related qualifications

Reject only if the document is clearly NOT a CV/resume:
- school assignment
- university report
- article
- contract
- invoice
- random notes
- presentation
- job description
- company profile
- cover letter only
- too short or unclear document

Important:
Do not reject a CV because it contains personal details, education, work experience, skills, or projects.
These are normal CV sections.

Return exactly:
{
  "isCV": true,
  "confidence": 90,
  "reason": "Short reason here"
}

Document text:
""" + safeCvText;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return invalidCVJson(pickByLanguage(language,
                        "Unable to validate document.",
                        "تعذّر التحقق من صحة المستند.",
                        "לא ניתן היה לאמת את המסמך."), 0);
            }

            JsonNode json = objectMapper.readTree(result);

            boolean isCV = json.path("isCV").asBoolean(false);
            int confidence = parseConfidence(json.path("confidence"));
            String reason = json.path("reason").asText("");

            ObjectNode fixedJson = objectMapper.createObjectNode();
            fixedJson.put("isCV", isCV);
            fixedJson.put("confidence", confidence);
            fixedJson.put("reason", reason);

            return objectMapper.writeValueAsString(fixedJson);

        } catch (HttpClientErrorException e) {
            return invalidCVJson(pickByLanguage(language,
                    "OpenAI API Error: " + e.getStatusCode(),
                    "خطأ في واجهة OpenAI: " + e.getStatusCode(),
                    "שגיאת API של OpenAI: " + e.getStatusCode()), 0);
        } catch (Exception e) {
            return invalidCVJson(pickByLanguage(language,
                    "Error validating CV: " + e.getMessage(),
                    "حدث خطأ أثناء التحقق من السيرة الذاتية: " + e.getMessage(),
                    "אירעה שגיאה באימות קורות החיים: " + e.getMessage()), 0);
        }
    }

    private String pickByLanguage(String language, String en, String ar, String he) {
        return switch (language == null ? "en" : language) {
            case "ar" -> ar;
            case "he" -> he;
            default -> en;
        };
    }

    // jobFingerprints/retryFeedback exist to support JobMatchService's per-job validation loop:
    // jobFingerprints is the caller-computed (jobId + title + company + normalized title +
    // content hash) fingerprint for each job, which the AI must echo back unchanged - this is
    // what lets the caller detect a verdict that got attached to the wrong job internally, even
    // within a single-job request (a garbled/omitted echo is itself a red flag). retryFeedback
    // is null on the first attempt; on a validation-triggered retry it carries the caller's
    // itemized complaints about the previous response, so the model corrects the SAME problems
    // instead of the caller just hoping a second blind attempt happens to come out right.
    public String computeJobMatches(
            CVAnalysis analysis, List<Job> jobs, Map<Long, String> jobFingerprints,
            String language, String retryFeedback) {
        try {
            if (jobs == null || jobs.isEmpty()) {
                return "{\"matches\":[]}";
            }

            // Deliberately small: batching many jobs into one prompt is what previously let the
            // model attach one job's verdict to a different job's jobId. JobMatchService now
            // defaults to one job per call; this cap is a hard backstop, not the primary control.
            List<Job> cappedJobs = jobs.size() > 5 ? jobs.subList(0, 5) : jobs;

            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every \"matchReason\" value entirely in Arabic. Keep skill names as-is (do not translate skill/technology names).";
                case "he" -> "Write every \"matchReason\" value entirely in Hebrew. Keep skill names as-is (do not translate skill/technology names).";
                default -> "Write every \"matchReason\" value in English.";
            };

            String retryBlock = (retryFeedback == null || retryFeedback.isBlank())
                    ? ""
                    : "\nYOUR PREVIOUS RESPONSE FOR THIS REQUEST WAS REJECTED FOR THESE REASONS - FIX EVERY ONE OF THEM THIS TIME:\n"
                            + retryFeedback + "\n";

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """
""" + retryBlock + """

You are an expert technical recruiter. Compare ONE candidate against one or more job postings and score how well the candidate fits EACH job independently, using the candidate's STRUCTURED profile data below as the primary evidence - not just the free-text summary/strengths prose.

CANDIDATE PROFILE:
""" + buildCandidateProfileBlock(analysis) + """

JOB POSTINGS TO SCORE (score each one independently, based only on its own requirements/skills/description):
""" + buildJobsBlock(cappedJobs, jobFingerprints) + """

IMPORTANT - YOUR ROLE HERE: you are the ANALYST, not the CALCULATOR. You never invent a raw 0-100 fit number for anything below. For every judgment, you pick ONE label from a small fixed list - the backend then turns that label into a number using a fixed formula. This is what keeps the score for an unchanged candidate+job pair from drifting between requests: the same label always produces the same number, deterministically, outside of you.

STEP 1 - fieldRelationCloseness (professional-FIELD relevance only, never "is this a perfect match"):
This asks ONLY how closely related the candidate's documented professional field/background is to this job's profession. It is NOT asking whether the candidate is a great match, has every required skill, has enough experience, or holds the exact license this specific role prefers - those questions belong entirely to steps 2/3, which can and should come out low for a weak-but-related fit. A low score elsewhere is the correct outcome for a related-but-weak fit; "unrelated" here is reserved for genuinely different professions.

Weigh the candidate's ENTIRE structured profile - Profession title, Candidate field, Previous job titles, Technical/soft skills, Education evidence, Certifications evidence, Licenses evidence, and the free-text summary/strengths - together. Never decide this from a single data point in isolation.

On the JOB side, the posting's Title (and its Description, when the description actually describes real duties) is the authoritative signal for what profession the job IS - not its "Required skills" field. Real job postings are frequently filled out sloppily: a company may leave a stale, copy-pasted, or wrong "Required skills" list that has nothing to do with the role (e.g. a job titled "Doctor" or "Registered Nurse" listing "React, TypeScript" as its skills, left over from a different posting template). When a job's Title/Description clearly indicate one profession but its skills/requirements text looks unrelated or contradictory to that profession, trust the Title/Description for fieldRelationCloseness and treat the mismatched skills text as a data-entry error on the posting, not as evidence the job is secretly a different profession. That mismatch belongs entirely in STEP 2 (it will show up there as missing/unmatched skills, correctly penalizing a poorly-specified posting) - it must never by itself flip fieldRelationCloseness to "unrelated" when the Title/Description alone would clearly put the job in the candidate's field.

Choose exactly one of:
- "same_role": the job is the same specific profession/title as the candidate's (e.g. a licensed doctor CV against a "Physician" job).
- "same_specialization": same specific profession, different seniority or sub-specialty (e.g. doctor CV against a specific medical specialty role, or a general "Healthcare"/clinical support role for a doctor).
- "same_broad_field": related but a genuinely different specific role/license within the same broad field - the field/domain knowledge transfers, but the exact credential does not (e.g. a doctor CV against a "Nurse" job - both medicine, different license; an Information Systems graduate against a Junior QA/Automation Engineer, Support Engineer, ERP, or CRM implementation job - both IT, different specific role).
- "unrelated": genuinely different professions with no meaningful skill/knowledge transfer, OR a role that legally requires a specific license/degree the candidate's Education/Certifications/Licenses evidence shows no relevant credential for at all (e.g. a doctor CV against a "Software Developer" job with no software education/skills evidence; an Information Systems graduate against a "Senior Surgeon" job; a chef CV against a lawyer job).

If a candidate's structured profile shows real evidence (education, credentials, meaningful experience, or a described career transition) supporting a field OTHER than the primary "Candidate field" label, judge closeness against THAT field too - a candidate can be genuinely qualified in more than one field at once.

If fieldRelationCloseness is "unrelated" for a job: skip STEP 2/3 entirely for that job - leave every skill array empty and every required* field null, and write matchReason as ONE sentence that names BOTH the candidate's actual field/profession AND this job's actual profession (e.g. "Your background is in accounting, and this Electrician role requires a licensed trade unrelated to your field.") - never reuse a generic template sentence verbatim, it must read as specific to this exact candidate and this exact job.

STEP 2 - if fieldRelationCloseness is NOT "unrelated", classify this job's required skills (from its Required skills / Requirements / Description text):
- For each skill/requirement the posting lists, decide if it reads as MANDATORY (required, must-have, essential) or PREFERRED (nice-to-have, a plus, preferred, bonus) - if the posting gives no such cue, treat it as MANDATORY by default.
- matchedMandatorySkills / missingMandatorySkills: every MANDATORY skill, matched if the candidate's technical/soft skills or summary/strengths show that skill or a reasonable equivalent (abbreviations, translations, synonyms, or a broader skill that clearly implies it count as matched), missing otherwise.
- matchedPreferredSkills / missingPreferredSkills: same classification for PREFERRED skills.
- Use the EXACT wording from the job's skill/requirement text. Every skill must appear in exactly one of the four arrays. Empty arrays if the job lists no skills.

STEP 3 - classify these three job requirements ONLY if the posting actually states or clearly implies them; otherwise leave the field null/absent (a null requirement is excluded from the final score entirely, not treated as "candidate fails it"):
- requiredExperienceLevel: exactly one of "entry", "mid", "senior", or null if the posting states no seniority/years-of-experience expectation. A junior candidate applying to a senior posting in their own field should get "senior" here (which the backend will score appropriately low) - this must never influence fieldRelationCloseness.
- requiredEducationLevel: exactly one of "any_degree" (posting wants a degree, field unspecified), "relevant_degree" (posting wants a degree IN this specific field), or null if no degree is required. Never set this for general/vocational roles (cashier, retail, warehouse, driver, cleaner, security guard, etc.).
- requiredCertificationLevel: exactly one of "general_cert" (posting wants some relevant certification), "specific_license" (posting legally requires a named practice license, e.g. medical/nursing/bar/PE license), or null if neither is required. A missing certification/license here must lower ITS OWN score, not flip fieldRelationCloseness away from a shared broad field (e.g. doctor vs. nurse job above).

Return exactly this JSON structure:
{
  "matches": [
    { "jobId": 0, "jobTitle": "", "jobFingerprint": "", "fieldRelationCloseness": "unrelated", "matchReason": "",
      "matchedMandatorySkills": [], "missingMandatorySkills": [], "matchedPreferredSkills": [], "missingPreferredSkills": [],
      "requiredExperienceLevel": null, "requiredEducationLevel": null, "requiredCertificationLevel": null }
  ]
}

Rules:
- jobTitle: copy the EXACT "Title:" value given for that jobId above, unchanged.
- jobFingerprint: copy the EXACT "Fingerprint:" value given for that jobId above, unchanged. Both jobTitle and jobFingerprint are cross-checks, not scoring inputs - they are what lets the caller catch a verdict that got attached to the wrong job internally, so get them exactly right, character for character.
- fieldRelationCloseness: exactly one of "same_role", "same_specialization", "same_broad_field", "unrelated" - never any other value.
- matchReason: ONE concise sentence (max ~25 words), SPECIFIC to this exact job and candidate (name the actual job title/profession involved - never reuse a generic stock phrase across different jobs), written directly to the candidate ("you").
- requiredExperienceLevel/requiredEducationLevel/requiredCertificationLevel: use null (not a placeholder string) when the posting gives no signal for that requirement.
- Include exactly one entry per job listed above, using the exact jobId given.
""";

            Map<String, Object> body = Map.of(
                    "model", matchModel,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return "{\"matches\":[]}";
            }

            return result;

        } catch (Exception e) {
            // Swallowed on purpose (caller falls back to "no score yet" for this batch and
            // retries on the next request), but log it - otherwise a transient OpenAI failure
            // (rate limit, timeout, truncated JSON) silently drops every job in this batch
            // with zero trace of why.
            log.error("computeJobMatches failed against OpenAI", e);
            return "{\"matches\":[]}";
        }
    }

    // matchPercent/matchedSkills/missingSkills/fieldRelevance/experience/education/certification/
    // location scores are all GIVEN, not computed here - they come from JobMatchService's core,
    // backend-weighted computation (see MatchScoreCalculator), which is the single source of
    // truth shown on the job card. This method only fills in the narrative explanation (plus
    // languageMatchPercent, which is informational and not part of the weighted score) anchored
    // to that already-decided number, instead of a second, independently-judged score for the
    // same job.
    public String computeJobMatchDetail(
            CVAnalysis analysis, Job job, String language,
            int matchPercent, List<String> matchedSkills, List<String> missingSkills) {
        try {
            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every text field (whyGoodMatch, whyNotPerfectMatch, improvementSuggestions, recommendation) entirely in Arabic.";
                case "he" -> "Write every text field (whyGoodMatch, whyNotPerfectMatch, improvementSuggestions, recommendation) entirely in Hebrew.";
                default -> "Write every text field in English.";
            };

            String matchedSkillsText = matchedSkills.isEmpty() ? "none" : String.join(", ", matchedSkills);
            String missingSkillsText = missingSkills.isEmpty() ? "none" : String.join(", ", missingSkills);

            // Built with plain concatenation (not a text block) since it interpolates
            // matchPercent mid-sentence in several places - a text block's closing/reopening
            // """ must be followed by a line break, so it can't sit mid-line like this.
            String givenScoreBlock =
                    "This candidate's overall match for this job has ALREADY been determined: " + matchPercent + "% match.\n"
                    + "Already-matched skills: " + matchedSkillsText + "\n"
                    + "Already-identified missing skills: " + missingSkillsText + "\n\n"
                    + "Your job is NOT to re-decide the overall fit or the matched/missing skill lists - those are fixed and given above. "
                    + "Build a detailed, honest, personalized breakdown and explanation that is fully CONSISTENT with this exact " + matchPercent + "% score. "
                    + "Do not write anything that implies a meaningfully higher or lower fit than " + matchPercent + "% - if the score is low, "
                    + "whyGoodMatch/recommendation should not read as if this were a strong match, and if the score is high, whyNotPerfectMatch "
                    + "should focus on minor gaps rather than implying a poor fit.";

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are an expert career coach giving one candidate a deep, personalized evaluation of ONE specific job posting.

CANDIDATE PROFILE:
""" + buildCandidateProfileBlock(analysis) + """

JOB POSTING:
""" + buildSingleJobBlock(job) + """

""" + givenScoreBlock + """

Evaluate this candidate against this job thoroughly and honestly, the same way a senior recruiter would coach the candidate one-on-one.

Return exactly this JSON structure:
{
  "languageMatchPercent": 0,
  "whyGoodMatch": ["", ""],
  "whyNotPerfectMatch": ["", ""],
  "improvementSuggestions": ["", ""],
  "recommendation": "",
  "shouldApply": true
}

Rules:
- languageMatchPercent: integer 0-100, how well the candidate's language/communication skills match what this job needs (if no language requirement is evident, score based on general communication evidence in the profile, defaulting to a reasonable score rather than 0).
- recommendation: 2-3 sentences giving a clear, personal apply-or-not recommendation with reasoning, written directly to the candidate ("you"), consistent with the given """ + matchPercent + """
% score.
- whyGoodMatch: 2-4 short bullet points, each written directly to the candidate ("you"), citing concrete evidence from the candidate profile.
- whyNotPerfectMatch: 2-4 short bullet points explaining honestly what keeps this from being a perfect match. If the given score is very high, it is fine for this to focus on minor gaps.
- improvementSuggestions: 2-4 concrete, actionable bullet points on what the candidate could do to become a stronger fit for this exact job.
- shouldApply: true if the given score and analysis support applying despite any gaps, false only if the mismatch is severe.
""";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return "{}";
            }

            return result;

        } catch (Exception e) {
            return "{}";
        }
    }

    // Purely a readability transform of the posting's OWN text for the "About this job" display
    // - never a scoring input (match scoring always reads the full raw description directly, see
    // buildJobsBlock/buildSingleJobBlock) and never a substitute for it. Grounded strictly in the
    // given description; every field must come back empty/"" rather than invented when the
    // posting doesn't mention it - this is presenting the posting, not embellishing it. Cached by
    // the caller (see ExternalJobService#getOrGenerateAboutSummary) so this only runs once per
    // job per language, not on every view.
    public String summarizeJobDescription(String title, String companyName, String description, String language) {
        try {
            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every text value entirely in Arabic.";
                case "he" -> "Write every text value entirely in Hebrew.";
                default -> "Write every text value in English.";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are reformatting ONE job posting into a clean, scannable summary for a candidate reading it on a job board. You are NOT evaluating a candidate and NOT judging the job - just reorganizing THIS posting's own content into a clear structure.

JOB POSTING:
Title: """ + nullToNA(title) + """

Company: """ + nullToNA(companyName) + """

Full description:
""" + nullToNA(description) + """


CRITICAL RULE: use ONLY information that actually appears in the description above. Never invent, assume, or infer anything not stated or clearly implied by the text. If the posting doesn't mention something, leave that field empty ("" for text, [] for a list) - an empty field is the correct, honest answer, not a failure.

Return exactly this JSON structure:
{
  "roleOverview": "",
  "responsibilities": ["", ""],
  "requiredQualifications": ["", ""],
  "preferredQualifications": ["", ""],
  "experienceLevel": "",
  "workArrangement": "",
  "importantConditions": ["", ""]
}

Field instructions:
- roleOverview: 2-4 sentences explaining what this role is and what the team/product/company does, based only on the description.
- responsibilities: the main duties/what the person will actually do day-to-day, as concise bullet points using the posting's own details (not restating the title).
- requiredQualifications: mandatory/must-have skills, qualifications, or experience as stated in the posting - one bullet per distinct requirement.
- preferredQualifications: nice-to-have/bonus/preferred items explicitly marked as such in the posting. Empty array if the posting draws no must-have/nice-to-have distinction.
- experienceLevel: a short phrase (e.g. "5+ years", "Senior-level", "Entry-level / no experience required") ONLY if the posting states or clearly implies one - otherwise "".
- workArrangement: the posting's own stated work location/arrangement (e.g. "Fully remote", "Hybrid, 3 days/week in office", "On-site in Tel Aviv") - otherwise "".
- importantConditions: other conditions worth flagging - e.g. visa/work-authorization requirements, travel expectations, on-call/shift requirements, contract vs. full-time, salary/equity notes NOT already shown elsewhere. Empty array if none.
""";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return "{}";
            }

            return result;

        } catch (Exception e) {
            return "{}";
        }
    }

    public String computeCandidateSummary(CVAnalysis analysis, Job job, String language) {
        try {
            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every text field (professionalBackground, strengths, weaknesses, overallSuitability) entirely in Arabic. Keep skill names in \"keySkills\" as-is (do not translate skill/technology names).";
                case "he" -> "Write every text field (professionalBackground, strengths, weaknesses, overallSuitability) entirely in Hebrew. Keep skill names in \"keySkills\" as-is (do not translate skill/technology names).";
                default -> "Write every text field in English.";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are a senior recruiter writing a candidate briefing for a hiring manager who is reviewing this candidate for ONE specific job opening. Refer to the candidate in the third person (e.g. "the candidate", "they") — never address the candidate directly with "you".

CANDIDATE PROFILE:
""" + buildCandidateProfileBlock(analysis) + """

JOB POSTING THE CANDIDATE APPLIED TO:
""" + buildSingleJobBlock(job) + """

Return exactly this JSON structure:
{
  "professionalBackground": "",
  "keySkills": ["", ""],
  "yearsOfExperience": "",
  "strengths": "",
  "weaknesses": "",
  "overallSuitability": "",
  "matchScore": 0
}

Field instructions:
- professionalBackground: 2-4 sentences summarizing the candidate's profession, field, and career stage, based only on the candidate profile above.
- keySkills: an array of 5-10 of the candidate's most relevant technical/professional skills, most relevant to this job first, using the exact wording from the candidate's skills where possible.
- yearsOfExperience: a short estimate such as "0-1 years", "2-4 years", "5-7 years", "8+ years", or "Not clearly stated in the CV" if it cannot be reasonably estimated.
- strengths: 2-4 sentences on the candidate's main strengths relevant to this specific job.
- weaknesses: 2-4 sentences honestly describing potential weaknesses or missing skills relative to THIS job's requirements. If the candidate profile shows no missing skills for this job, note that clearly instead of inventing gaps.
- overallSuitability: EXACTLY 3 to 5 sentences giving the hiring manager a clear, balanced verdict on this candidate's overall suitability for this specific job, referencing concrete evidence from the candidate profile and job requirements.
- matchScore: integer 0-100 measuring how well the candidate's ACTUAL profile matches THIS SPECIFIC job's requirements — this is a job-fit comparison, not a general CV quality score. Base it on ALL of the following, compared directly against the job's required skills, requirements, and description: (1) technical/professional skills overlap, (2) relevant work experience and its depth, (3) education/certifications relevant to the role, and (4) overall domain/field alignment.
  Scoring rules:
  - If the candidate's profile shows none (or almost none) of the job's core required skills/technologies/domain (e.g. the job requires Java, Spring Boot, SQL, and REST APIs for a backend role, and the candidate has no backend/programming experience at all), score MUST be low, in the 10-30 range.
  - If the candidate has some overlapping skills but is missing several important requirements (skills, experience, or education), score in the 30-60 range.
  - If the candidate covers most of the job's requirements with relevant experience and adequate education/certifications, score in the 60-84 range.
  - If the candidate strongly matches nearly all required skills, experience level, and education/certification expectations, score 85-100.
  - Be realistic, honest, and consistent: given the same candidate profile and the same job, you must always return the same score — do not vary it randomly between runs. Do not inflate the score because the candidate has a strong CV in an unrelated field — this score is specifically about fit for THIS job.
""";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            if (result == null || result.isBlank()) {
                return "{}";
            }

            return result;

        } catch (Exception e) {
            return "{}";
        }
    }

    private String buildSingleJobBlock(Job job) {
        // No truncation - this explanation call must be grounded in the same complete
        // posting the match score itself was computed from (see buildJobsBlock).
        String description = job.getDescription();

        return """
Title: %s
Type: %s
Location: %s
Required skills: %s
Requirements: %s
Description: %s
""".formatted(
                nullToNA(job.getTitle()),
                nullToNA(job.getType()),
                nullToNA(job.getLocation()),
                nullToNA(job.getSkills()),
                nullToNA(job.getRequirements()),
                nullToNA(description)
        );
    }

    public String explainSkill(String skillName, String jobTitle, String language) {
        try {
            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every text field entirely in Arabic.";
                case "he" -> "Write every text field entirely in Hebrew.";
                default -> "Write every text field in English.";
            };

            String contextLine = (jobTitle == null || jobTitle.isBlank())
                    ? ""
                    : "For context, a job seeker encountered this skill while looking at a \"" + jobTitle + "\" job posting, but keep your explanation broadly useful beyond this one job.\n";

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are a career coach explaining a professional skill to a job seeker who is missing it from their profile.

Skill: """ + skillName + "\n" + contextLine + """

Return exactly this JSON structure:
{
  "whyImportant": "",
  "whereUsed": ["", ""],
  "recommendedResources": ["", ""],
  "learningTips": ["", ""]
}

Rules:
- whyImportant: 1-2 sentences explaining why this skill matters to employers, written directly to the reader ("you").
- whereUsed: 2-4 short bullet points describing where/how this skill is typically used in real work.
- recommendedResources: 3-5 short bullet points, each "Name — one line description", listing FREE or freemium learning resources only (official documentation, freeCodeCamp, MDN, Coursera/YouTube free courses, etc.). Do not invent resources or URLs that do not exist; prefer well-known official sources.
- learningTips: 2-4 short, actionable bullet points on how to start learning this skill effectively.
""";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", prompt,
                    "store", false,
                    "temperature", 0,
                    "text", Map.of("format", Map.of("type", "json_object"))
            );

            Map<String, Object> response = callOpenAI(body);
            String result = extractTextFromOpenAIResponse(response);

            return (result == null || result.isBlank()) ? "{}" : result;
        } catch (Exception e) {
            return "{}";
        }
    }

    // Every field here is a PERSISTED, structured value from the CV-analysis step - never
    // something the job matcher has to infer solely from free-text prose. This is what lets the
    // regulated-profession fieldRelated guardrail actually check "does this candidate have a
    // relevant license" instead of hoping the summary paragraph happened to mention one.
    private String buildCandidateProfileBlock(CVAnalysis analysis) {
        return """
Candidate field (broad label - the candidate's real background may span more than this one field; read the fields below for the full picture): %s
Profession title: %s
Years of experience: %s
Experience level (none / entry_level / mid_level / senior_level): %s
Previous job titles (most recent first): %s
Technical skills: %s
Soft skills: %s
Languages: %s
Education evidence (none / general / relevant_degree): %s
Certifications evidence (none / general / field_relevant): %s
Licenses evidence - formal legal practice license/registration for a regulated profession, e.g. medical license, nursing registration, bar admission, PE license (none / in_progress / licensed): %s
Summary (career stage, education, and overall profile): %s
Strengths (qualifications, education/certifications, career progression, and field-specific experience): %s
Missing skills: %s
Roles a career advisor already identified as a good fit for this candidate (strong signal for fieldRelated - if a job posting's title/role closely matches one of these, that is direct evidence supporting fieldRelated=true even if the candidate's most recent paid job was in an unrelated field, e.g. a career changer): %s
Overall CV score: %s
""".formatted(
                nullToNA(analysis.getCandidateField()),
                nullToNA(analysis.getProfessionTitle()),
                nullToNA(analysis.getYearsOfExperience()),
                nullToNA(analysis.getExperienceLevel()),
                nullToNA(analysis.getPreviousJobTitles()),
                nullToNA(analysis.getTechnicalSkills()),
                nullToNA(analysis.getSoftSkills()),
                nullToNA(analysis.getLanguages()),
                nullToNA(analysis.getEducationEvidence()),
                nullToNA(analysis.getCertificationsEvidence()),
                nullToNA(analysis.getLicensesEvidence()),
                nullToNA(analysis.getSummary()),
                nullToNA(analysis.getStrengths()),
                nullToNA(analysis.getMissingSkills()),
                nullToNA(analysis.getRecommendedRoles()),
                nullToNA(analysis.getOverallScore())
        );
    }

    private String buildJobsBlock(List<Job> jobs, Map<Long, String> jobFingerprints) {
        StringBuilder sb = new StringBuilder();

        for (Job job : jobs) {
            // No truncation here anymore - match scoring must see the COMPLETE posting.
            // External jobs have no separate skills/requirements field from any provider (see
            // JobicyJobProvider#resolveDescription), so the description is the only source of
            // real requirement signal; truncating it was found via live testing to cut off
            // requirements sections that start well past character 2000. The one remaining
            // safety ceiling lives at ingestion time (ExternalJob#description is TEXT, capped
            // only defensively at 20,000 chars in JobicyJobProvider).
            String description = job.getDescription();

            String fingerprint = jobFingerprints == null ? null : jobFingerprints.get(job.getId());

            sb.append("---\n")
                    .append("jobId: ").append(job.getId()).append("\n")
                    .append("Fingerprint: ").append(nullToNA(fingerprint)).append("\n")
                    .append("Title: ").append(nullToNA(job.getTitle())).append("\n")
                    .append("Company: ").append(nullToNA(job.getCompanyName())).append("\n")
                    .append("Type: ").append(nullToNA(job.getType())).append("\n")
                    .append("Location: ").append(nullToNA(job.getLocation())).append("\n")
                    .append("Required skills: ").append(nullToNA(job.getSkills())).append("\n")
                    .append("Requirements: ").append(nullToNA(job.getRequirements())).append("\n")
                    .append("Description: ").append(nullToNA(description)).append("\n");
        }

        return sb.toString();
    }

    private String nullToNA(String value) {
        return (value == null || value.isBlank()) ? "N/A" : value;
    }

    private String nullToNA(Integer value) {
        return value == null ? "N/A" : String.valueOf(value);
    }

    private int parseConfidence(JsonNode confidenceNode) {
        if (confidenceNode == null || confidenceNode.isMissingNode() || confidenceNode.isNull()) {
            return 0;
        }

        if (confidenceNode.isNumber()) {
            return confidenceNode.asInt(0);
        }

        String text = confidenceNode.asText("").replace("%", "").trim();

        try {
            return Integer.parseInt(text);
        } catch (Exception e) {
            return 0;
        }
    }

    private int calculateRealisticScore(JsonNode json) {
        int aiScore = clampScore(json.path("overallProfileScore").asInt(-1));
        int evidenceScore = calculateEvidenceScore(json);

        if (aiScore < 0) {
            return evidenceScore;
        }

        int score = Math.round((aiScore * 0.65f) + (evidenceScore * 0.35f));

        String experience = json.path("professionalExperienceEvidence").asText("").toLowerCase().trim();
        int experienceDepth = Math.max(0, Math.min(5, json.path("experienceDepthScore").asInt(0)));
        String careerStage = json.path("careerStage").asText("").toLowerCase().trim();

        // Guardrails prevent rich professional CVs from being mis-scored like empty student CVs.
        if (experience.equals("senior_level") || careerStage.equals("senior") || careerStage.equals("managerial")) {
            score = Math.max(score, experienceDepth >= 3 ? 70 : 62);
        } else if (experience.equals("mid_level") || careerStage.equals("experienced")) {
            score = Math.max(score, experienceDepth >= 3 ? 58 : 50);
        } else if (experience.equals("entry_level")) {
            score = Math.max(score, 38);
        }

        return Math.min(score, 100);
    }

    private int calculateEvidenceScore(JsonNode json) {
        int score = 0;

        String experience = json.path("professionalExperienceEvidence").asText("").toLowerCase().trim();
        int expBase = switch (experience) {
            case "senior_level" -> 34;
            case "mid_level" -> 25;
            case "entry_level" -> 14;
            default -> 0;
        };
        int expDepth = Math.max(0, Math.min(5, json.path("experienceDepthScore").asInt(0)));
        score += Math.min(expBase + (expDepth * 3), 40);

        String portfolio = json.path("portfolioEvidence").asText("").toLowerCase().trim();
        int workEvidence = switch (portfolio) {
            case "strong" -> 18;
            case "relevant" -> 13;
            case "basic" -> 7;
            default -> 0;
        };
        int portfolioDepth = Math.max(0, Math.min(5, json.path("portfolioDetailScore").asInt(0)));
        score += Math.min(workEvidence + portfolioDepth, 22);

        String education = json.path("educationEvidence").asText("").toLowerCase().trim();
        score += switch (education) {
            case "relevant_degree" -> 10;
            case "general" -> 5;
            default -> 0;
        };

        String certifications = json.path("certificationsEvidence").asText("").toLowerCase().trim();
        score += switch (certifications) {
            case "field_relevant" -> 8;
            case "general" -> 4;
            default -> 0;
        };

        String leadership = json.path("leadershipEvidence").asText("").toLowerCase().trim();
        score += switch (leadership) {
            case "strong" -> 8;
            case "clear" -> 6;
            case "implicit" -> 3;
            default -> 0;
        };

        String communication = json.path("communicationEvidence").asText("").toLowerCase().trim();
        score += switch (communication) {
            case "strong" -> 6;
            case "clear" -> 4;
            case "implicit" -> 2;
            default -> 0;
        };

        String achievements = json.path("achievementEvidence").asText("").toLowerCase().trim();
        score += switch (achievements) {
            case "measurable" -> 6;
            case "general" -> 3;
            default -> 0;
        };

        int skillsCount = countItems(json.path("skills").asText(""));
        if (skillsCount >= 8) score += 6;
        else if (skillsCount >= 5) score += 4;
        else if (skillsCount >= 2) score += 2;

        return Math.min(score, 100);
    }

    private int clampScore(int score) {
        if (score < 0) return -1;
        return Math.max(0, Math.min(score, 100));
    }
    private int countItems(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        String[] parts = text.split("[,;\\n]");
        int count = 0;

        for (String part : parts) {
            if (!part.trim().isBlank()) {
                count++;
            }
        }

        return count;
    }

    private String getScoreLevel(int score, String language) {
        if (score <= 20) {
            return pickByLanguage(language, "Very weak CV", "سيرة ذاتية ضعيفة جدًا", "קורות חיים חלשים מאוד");
        } else if (score <= 40) {
            return pickByLanguage(language, "Beginner level", "مستوى مبتدئ", "רמת מתחיל");
        } else if (score <= 60) {
            return pickByLanguage(language, "Junior potential", "إمكانات لمرشح مبتدئ", "פוטנציאל ג'וניור");
        } else if (score <= 75) {
            return pickByLanguage(language, "Good junior candidate", "مرشح مبتدئ جيد", "מועמד ג'וניור טוב");
        } else if (score <= 90) {
            return pickByLanguage(language, "Strong candidate", "مرشح قوي", "מועמד חזק");
        } else {
            return pickByLanguage(language, "Excellent candidate", "مرشح ممتاز", "מועמד מצוין");
        }
    }

    private String buildEvaluationReason(JsonNode json, int score, String language) {
        if (!"en".equals(language == null ? "en" : language)) {
            return buildEvaluationReasonLocalized(json, language);
        }

        String candidateField = json.path("candidateField").asText("other").toLowerCase().trim();
        String experience = json.path("professionalExperienceEvidence").asText("none").toLowerCase().trim();
        String portfolio = json.path("portfolioEvidence").asText("none").toLowerCase().trim();
        String education = json.path("educationEvidence").asText("none").toLowerCase().trim();
        String achievements = json.path("achievementEvidence").asText("none").toLowerCase().trim();

        StringBuilder reason = new StringBuilder();

        // Education
        if (education.equals("relevant_degree")) {
            reason.append("Your relevant degree gives you a strong academic foundation for your field. ");
        } else if (education.equals("general")) {
            reason.append("Your educational background shows a commitment to learning. ");
        }

        // Experience — field-agnostic language
        switch (experience) {
            case "senior_level" -> reason.append("Your senior-level professional experience is a significant advantage that sets you apart from many candidates in your field. ");
            case "mid_level" -> reason.append("Your solid professional experience shows you can contribute effectively in a real work environment — that matters to employers. ");
            case "entry_level" -> reason.append("Your early professional experience shows initiative and a willingness to grow, which employers in your field value. ");
            default -> {
                String tip = switch (candidateField) {
                    case "construction" -> "Adding documented site experience, apprenticeships, or relevant certifications would meaningfully strengthen your profile.";
                    case "healthcare" -> "Adding clinical experience, volunteer roles, or medical certifications would meaningfully strengthen your profile.";
                    case "education" -> "Adding teaching experience, tutoring roles, or curriculum projects would meaningfully strengthen your profile.";
                    default -> "Adding work experience — even a part-time role or internship — would meaningfully strengthen your profile.";
                };
                reason.append(tip).append(" ");
            }
        }

        // Portfolio/work evidence — field-aware language
        String workLabel = switch (candidateField) {
            case "construction" -> "projects and sites";
            case "healthcare" -> "cases and clinical work";
            case "education" -> "courses and educational programs";
            case "legal" -> "cases and legal work";
            case "software" -> "projects";
            case "sales" -> "sales activity and customer results";
            case "customer_support" -> "customer cases and service outcomes";
            case "administration" -> "administrative processes and coordination work";
            case "hospitality" -> "service, kitchen, or event work";
            case "cleaning" -> "facilities, standards, and cleaning responsibilities";
            default -> "work examples";
        };

        switch (portfolio) {
            case "strong" -> reason.append("Your well-documented ").append(workLabel).append(" are a real highlight and demonstrate that you can deliver concrete results. ");
            case "relevant" -> reason.append("Your ").append(workLabel).append(" show initiative and relevant skills — adding more detail about outcomes would make them even more impressive. ");
            case "basic" -> reason.append("You have started documenting your ").append(workLabel).append(", which is the right instinct — more detail about what you achieved would increase their impact. ");
            default -> {
                String tip = switch (candidateField) {
                    case "construction" -> "Adding documented projects, sites managed, or equipment operated would significantly strengthen your CV.";
                    case "healthcare" -> "Adding clinical cases, procedures performed, or patient outcomes would significantly strengthen your CV.";
                    case "education" -> "Adding courses taught, programs developed, or student outcomes would significantly strengthen your CV.";
                    case "sales" -> "Adding sales targets, customer segments, or revenue outcomes would significantly strengthen your CV.";
                    case "customer_support" -> "Adding support volume, tools, customer satisfaction, or resolved case examples would significantly strengthen your CV.";
                    case "administration" -> "Adding administrative systems, reports, schedules, or coordination examples would significantly strengthen your CV.";
                    case "hospitality" -> "Adding service volume, menu/event details, kitchen responsibilities, or shift leadership would significantly strengthen your CV.";
                    case "cleaning" -> "Adding facility types, cleaning standards, equipment, or responsibility scope would significantly strengthen your CV.";
                    default -> "Adding concrete examples of your work would significantly strengthen your CV.";
                };
                reason.append(tip).append(" ");
            }
        }

        // Achievements
        if (achievements.equals("measurable")) {
            reason.append("Including measurable achievements sets your CV apart and makes your contributions concrete and credible.");
        } else if (achievements.equals("general")) {
            reason.append("Try to add numbers or outcomes to your achievements where possible — even rough figures make a real difference.");
        } else {
            reason.append("Look for any results, wins, or numbers you can add to your CV — even small measurable outcomes help you stand out.");
        }

        return reason.toString().trim();
    }

    private String buildEvaluationReasonLocalized(JsonNode json, String language) {
        String experience = json.path("professionalExperienceEvidence").asText("none").toLowerCase().trim();
        String portfolio = json.path("portfolioEvidence").asText("none").toLowerCase().trim();
        String education = json.path("educationEvidence").asText("none").toLowerCase().trim();
        String achievements = json.path("achievementEvidence").asText("none").toLowerCase().trim();

        StringBuilder reason = new StringBuilder();

        if (education.equals("relevant_degree")) {
            reason.append(pickByLanguage(language,
                    "", "خلفيتك التعليمية ذات الصلة تمنحك أساسًا أكاديميًا قويًا في مجالك. ",
                    "הרקע האקדמי הרלוונטי שלך מעניק לך בסיס אקדמי חזק בתחומך. "));
        } else if (education.equals("general")) {
            reason.append(pickByLanguage(language,
                    "", "خلفيتك التعليمية تعكس التزامًا بالتعلم. ",
                    "הרקע ההשכלתי שלך משקף מחויבות ללמידה. "));
        }

        switch (experience) {
            case "senior_level" -> reason.append(pickByLanguage(language,
                    "", "خبرتك المهنية العليا تمثل ميزة كبيرة تميزك عن العديد من المرشحين في مجالك. ",
                    "הניסיון המקצועי הבכיר שלך הוא יתרון משמעותי שמייחד אותך ממועמדים רבים אחרים בתחומך. "));
            case "mid_level" -> reason.append(pickByLanguage(language,
                    "", "خبرتك المهنية الجيدة تُظهر قدرتك على المساهمة الفعالة في بيئة عمل حقيقية. ",
                    "הניסיון המקצועי המוצק שלך מראה שאתה יכול לתרום ביעילות בסביבת עבודה אמיתית. "));
            case "entry_level" -> reason.append(pickByLanguage(language,
                    "", "خبرتك المبكرة تُظهر مبادرة ورغبة في التطور، وهو ما يقدّره أصحاب العمل في مجالك. ",
                    "הניסיון המוקדם שלך מראה יוזמה ורצון להתפתח, דבר שמעסיקים בתחומך מעריכים. "));
            default -> reason.append(pickByLanguage(language,
                    "", "إضافة خبرة عملية، حتى لو كانت دورًا بدوام جزئي أو تدريبًا، ستعزز ملفك الشخصي بشكل ملموس. ",
                    "הוספת ניסיון תעסוקתי, אפילו תפקיד חלקי או התמחות, תחזק משמעותית את הפרופיל שלך. "));
        }

        switch (portfolio) {
            case "strong" -> reason.append(pickByLanguage(language,
                    "", "أعمالك الموثقة جيدًا تُعد نقطة قوة حقيقية وتُظهر قدرتك على تحقيق نتائج ملموسة. ",
                    "העבודה המתועדת היטב שלך היא נקודת חוזק אמיתית ומדגימה שאתה יכול לספק תוצאות מוחשיות. "));
            case "relevant" -> reason.append(pickByLanguage(language,
                    "", "أعمالك تُظهر مبادرة ومهارات ذات صلة — إضافة تفاصيل حول النتائج ستجعلها أكثر تأثيرًا. ",
                    "העבודה שלך מראה יוזמה וכישורים רלוונטיים — הוספת פרטים על התוצאות תגביר את ההשפעה שלה. "));
            case "basic" -> reason.append(pickByLanguage(language,
                    "", "لقد بدأت بتوثيق أعمالك، وهي خطوة صحيحة — إضافة تفاصيل حول ما حققته ستزيد من تأثيرها. ",
                    "התחלת לתעד את העבודה שלך, וזו אינסטינקט נכון — הוספת פרטים על מה שהשגת תגביר את ההשפעה. "));
            default -> reason.append(pickByLanguage(language,
                    "", "إضافة أمثلة ملموسة على عملك ستعزز سيرتك الذاتية بشكل كبير. ",
                    "הוספת דוגמאות מוחשיות לעבודה שלך תחזק משמעותית את קורות החיים שלך. "));
        }

        if (achievements.equals("measurable")) {
            reason.append(pickByLanguage(language,
                    "", "تضمين إنجازات قابلة للقياس يميز سيرتك الذاتية ويجعل مساهماتك ملموسة وموثوقة.",
                    "כלילת הישגים מדידים בולטת בקורות החיים שלך והופכת את התרומות שלך למוחשיות ואמינות."));
        } else if (achievements.equals("general")) {
            reason.append(pickByLanguage(language,
                    "", "حاول إضافة أرقام أو نتائج إلى إنجازاتك حيثما أمكن — حتى الأرقام التقريبية تُحدث فرقًا حقيقيًا.",
                    "נסה להוסיף מספרים או תוצאות להישגים שלך במידת האפשר — אפילו נתונים משוערים עושים הבדל אמיתי."));
        } else {
            reason.append(pickByLanguage(language,
                    "", "ابحث عن أي نتائج أو إنجازات أو أرقام يمكنك إضافتها إلى سيرتك الذاتية — حتى النتائج الصغيرة القابلة للقياس تساعدك على التميز.",
                    "חפש כל תוצאה, הצלחה או מספר שתוכל להוסיף לקורות החיים שלך — אפילו תוצאות מדידות קטנות עוזרות לך לבלוט."));
        }

        return reason.toString().trim();
    }

    private Map<String, Object> callOpenAI(Map<String, Object> body) {
        String configuredApiKey = requireConfiguredApiKey();

        Map<String, Object> response = restClient.post()
                .uri("/v1/responses")
                .header("Authorization", "Bearer " + configuredApiKey)
                .header("Content-Type", "application/json")
                .body(Objects.requireNonNull(body))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        return response != null ? response : Map.of();
    }

    private String requireConfiguredApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured. Set it in your terminal environment or in a local .env file.");
        }

        return apiKey.trim();
    }

    private String extractTextFromOpenAIResponse(Map<String, Object> response) {
        if (response != null && response.containsKey("output")) {
            Object outputObj = response.get("output");

            if (outputObj instanceof List<?> outputList && !outputList.isEmpty()) {
                Object firstItem = outputList.get(0);

                if (firstItem instanceof Map<?, ?> messageMap && messageMap.containsKey("content")) {
                    Object contentObj = messageMap.get("content");

                    if (contentObj instanceof List<?> contentList && !contentList.isEmpty()) {
                        Object contentItem = contentList.get(0);

                        if (contentItem instanceof Map<?, ?> contentMap && contentMap.containsKey("text")) {
                            Object text = contentMap.get("text");

                            if (text != null && !text.toString().trim().isEmpty()) {
                                return text.toString()
                                        .trim()
                                        .replace("```json", "")
                                        .replace("```", "")
                                        .trim();
                            }
                        }
                    }
                }
            }
        }

        return "";
    }

    private String emptyAnalysisJson(String message) {
        return """
{
  "candidateField": "",
  "skills": "",
  "summary": "%s",
  "strengths": "",
  "missingSkills": "",
  "recommendedRoles": "",
  "overallScore": "",
  "scoreLevel": "",
  "evaluationReason": "",
  "missingInformation": ""
}
""".formatted(escapeJson(message));
    }

    private String invalidCVJson(String reason, int confidence) {
        return """
{
  "isCV": false,
  "confidence": %d,
  "reason": "%s"
}
""".formatted(confidence, escapeJson(reason));
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
