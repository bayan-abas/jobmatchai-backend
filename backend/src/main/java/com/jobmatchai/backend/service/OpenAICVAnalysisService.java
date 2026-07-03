package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.Job;

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

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .build();

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
  "leadershipEvidence": "",
  "communicationEvidence": "",
  "achievementEvidence": "",
  "cvQualityIssues": "",
  "overallProfileScore": 0,
  "scoreRationale": ""
}

Field instructions:

1. candidateField: Identify the primary field based on the CV. Use exactly one English value:
   "software", "construction", "healthcare", "education", "business", "finance", "legal", "trades", "hospitality", "manufacturing", "sales", "customer_support", "administration", "operations", "cleaning", "other".

2. professionTitle: The best specific profession/title for this candidate, such as "Construction Manager", "Accountant", "Teacher", "Nurse", "Chef", "Cleaner", "Sales Representative", "Administrative Employee", "Software Developer".

3. careerStage: exactly one of "entry", "experienced", "senior", "managerial", "career_transition".

4. estimatedYearsExperience: Use the CV dates/history. Return a short text such as "0-1", "2-4", "5-7", "8+", or "unclear but experienced".

5. skills: A comma-separated list of skills and professional capabilities explicitly supported by the CV. Include field-specific practical skills and soft/professional skills when evidenced by roles or responsibilities. For example: site supervision, budgeting, lesson planning, patient care, food preparation, customer service, office administration, sales negotiation, team leadership.

6. summary: Write 4-5 warm, specific sentences directly to the candidate. Explain the profession you identified, career stage, what makes the profile valuable, and the main opportunity to improve. Mention concrete evidence from the CV.

7. strengths: Write 5-7 sentences of natural prose, not bullets. Cover what the candidate is doing well, what makes them valuable, strongest qualifications, responsibilities, career progression, education/certifications, communication/leadership, and practical field experience where present. Tie each point to the candidate's actual CV.

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

14. certificationsEvidence: exactly one of "none", "general", "field_relevant".

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
            finalJson.put("skills", rawSkills);
            finalJson.put("summary", json.path("summary").asText(""));
            finalJson.put("strengths", json.path("strengths").asText(""));
            finalJson.put("missingSkills", filteredMissingStr);
            finalJson.put("recommendedRoles", json.path("recommendedRoles").asText(""));
            finalJson.put("overallScore", String.valueOf(score));
            finalJson.put("scoreLevel", scoreLevel);
            finalJson.put("evaluationReason", evaluationReason);
            finalJson.put("missingInformation", json.path("cvQualityIssues").asText(""));

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

    public String computeJobMatches(CVAnalysis analysis, List<Job> jobs, String language) {
        try {
            if (jobs == null || jobs.isEmpty()) {
                return "{\"matches\":[]}";
            }

            List<Job> cappedJobs = jobs.size() > 50 ? jobs.subList(0, 50) : jobs;

            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every \"matchReason\" value entirely in Arabic. Keep skill names in \"matchedSkills\"/\"missingSkills\" as-is (do not translate skill/technology names).";
                case "he" -> "Write every \"matchReason\" value entirely in Hebrew. Keep skill names in \"matchedSkills\"/\"missingSkills\" as-is (do not translate skill/technology names).";
                default -> "Write every \"matchReason\" value in English.";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are an expert technical recruiter. Compare ONE candidate against MULTIPLE job postings and score how well the candidate fits EACH job independently.

CANDIDATE PROFILE:
""" + buildCandidateProfileBlock(analysis) + """

JOB POSTINGS TO SCORE (score each one independently, based only on its own requirements/skills/description):
""" + buildJobsBlock(cappedJobs) + """

For EACH job listed above, FIRST decide "fieldRelated": set it to false ONLY if the candidate's profession/field and this job's profession/field are fundamentally unrelated — completely different professions where no meaningful skill/experience comparison is possible (e.g. a chef candidate vs. a lawyer job, a nurse candidate vs. a software engineer job, an accountant candidate vs. an electrician job). Set it to true for anything else, including jobs that are a weak or partial match within a related or comparable field.

If "fieldRelated" is false for a job: set its matchPercent to null, matchedSkills and missingSkills to empty arrays, and matchReason to ONE short sentence explaining the field mismatch (e.g. "This job belongs to a completely different professional field."). Skip the scoring/classification steps below for that job.

If "fieldRelated" is true, evaluate fit based on: skills overlap, relevant experience/seniority match, field/domain match, and overall qualification for that specific job's requirements. Do not give every job a similar score — scores must vary meaningfully based on actual fit. A job requiring skills the candidate doesn't have should score low; a job matching the candidate's strongest skills and experience should score high.

Also classify EACH skill listed in that job's "Required skills" line into either matchedSkills or missingSkills:
- Treat a job skill as MATCHED if the candidate's skills/summary/strengths show they clearly have that skill OR a reasonable equivalent — even if worded, abbreviated, or formatted differently (e.g. "JS" = "JavaScript", "React.js" = "React", "Postgres" = "PostgreSQL", "Node" = "Node.js", translated/synonymous terms, common abbreviations, or a broader skill that clearly implies it).
- Treat a job skill as MISSING only if the candidate shows no reasonable evidence of that skill or an equivalent.
- Every skill from that job's "Required skills" line must appear in exactly one of matchedSkills or missingSkills, using the EXACT wording from the job's "Required skills" line (do not rewrite/rename it).
- If a job has no listed skills, both arrays should be empty for that job.

Return exactly this JSON structure:
{
  "matches": [
    { "jobId": 0, "fieldRelated": true, "matchPercent": 0, "matchReason": "", "matchedSkills": [], "missingSkills": [] }
  ]
}

Rules:
- fieldRelated: boolean, per the rule above.
- matchPercent: integer 0-100 when fieldRelated is true; null when fieldRelated is false.
- matchReason: ONE concise sentence (max ~20 words) explaining the score (or the field mismatch), written directly to the candidate ("you").
- matchedSkills / missingSkills: arrays of strings, exact wording from the job's skill list, no duplicates between the two arrays. Empty arrays when fieldRelated is false.
- Include exactly one entry per job listed above, using the exact jobId given.
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
                return "{\"matches\":[]}";
            }

            return result;

        } catch (Exception e) {
            return "{\"matches\":[]}";
        }
    }

    public String computeJobMatchDetail(CVAnalysis analysis, Job job, String language) {
        try {
            String languageInstruction = switch (language == null ? "en" : language) {
                case "ar" -> "Write every text field (matchReason, whyGoodMatch, whyNotPerfectMatch, improvementSuggestions, recommendation) entirely in Arabic. Keep skill names in matchedSkills/missingSkills as-is (do not translate skill/technology names).";
                case "he" -> "Write every text field (matchReason, whyGoodMatch, whyNotPerfectMatch, improvementSuggestions, recommendation) entirely in Hebrew. Keep skill names in matchedSkills/missingSkills as-is (do not translate skill/technology names).";
                default -> "Write every text field in English.";
            };

            String prompt = """
Return ONLY a raw valid JSON object. No markdown. No explanations outside the JSON.
""" + languageInstruction + """

You are an expert career coach giving one candidate a deep, personalized evaluation of ONE specific job posting.

CANDIDATE PROFILE:
""" + buildCandidateProfileBlock(analysis) + """

JOB POSTING:
""" + buildSingleJobBlock(job) + """

FIRST decide "fieldRelated": set it to false ONLY if the candidate's profession/field and this job's profession/field are fundamentally unrelated — completely different professions where no meaningful skill/experience comparison is possible (e.g. a chef candidate vs. a lawyer job, a nurse candidate vs. a software engineer job, an accountant candidate vs. an electrician job). Set it to true for anything else, including a weak or partial match within a related or comparable field.

If "fieldRelated" is false: set matchPercent and skillsMatchPercent/experienceMatchPercent/educationMatchPercent/languageMatchPercent all to null, matchedSkills/missingSkills/whyGoodMatch/improvementSuggestions to empty arrays, matchReason to ONE short sentence explaining the field mismatch (e.g. "This job belongs to a completely different professional field."), whyNotPerfectMatch to one or two bullet points briefly noting the field mismatch (not skill gaps), recommendation to a short note that a meaningful evaluation isn't possible for this job, and shouldApply to false. Skip everything below for that job.

If "fieldRelated" is true, evaluate this candidate against this job thoroughly and honestly, the same way a senior recruiter would coach the candidate one-on-one.

Classify EACH skill listed in the job's "Required skills" line into either matchedSkills or missingSkills:
- Treat a job skill as MATCHED if the candidate's skills/summary/strengths show they clearly have that skill OR a reasonable equivalent — even if worded, abbreviated, or formatted differently (e.g. "JS" = "JavaScript", "React.js" = "React", "Postgres" = "PostgreSQL", "Node" = "Node.js", translated/synonymous terms, common abbreviations, or a broader skill that clearly implies it).
- Treat a job skill as MISSING only if the candidate shows no reasonable evidence of that skill or an equivalent.
- Every skill from the job's "Required skills" line must appear in exactly one of matchedSkills or missingSkills, using the EXACT wording from that line.
- If the job has no listed skills, both arrays should be empty.

Return exactly this JSON structure:
{
  "fieldRelated": true,
  "matchPercent": 0,
  "skillsMatchPercent": 0,
  "experienceMatchPercent": 0,
  "educationMatchPercent": 0,
  "languageMatchPercent": 0,
  "matchReason": "",
  "matchedSkills": [],
  "missingSkills": [],
  "whyGoodMatch": ["", ""],
  "whyNotPerfectMatch": ["", ""],
  "improvementSuggestions": ["", ""],
  "recommendation": "",
  "shouldApply": true
}

Rules:
- fieldRelated: boolean, per the rule above.
- matchPercent: integer 0-100, your holistic overall score, consistent with the matchedSkills/missingSkills classification and broadly in line with the four breakdown scores below (does not need to be their exact average).
- skillsMatchPercent: integer 0-100, how well the candidate's skills specifically cover this job's required skills.
- experienceMatchPercent: integer 0-100, how well the candidate's years/seniority/type of experience matches what this job needs.
- educationMatchPercent: integer 0-100, how well the candidate's education/certifications match what this job needs (if the job has no explicit education requirement, judge general adequacy for the role).
- languageMatchPercent: integer 0-100, how well the candidate's language/communication skills match what this job needs (if no language requirement is evident, score based on general communication evidence in the profile, defaulting to a reasonable score rather than 0).
- matchReason: ONE concise sentence (max ~20 words) explaining the score, written directly to the candidate ("you").
- matchedSkills / missingSkills: arrays of strings, exact wording from the job's skill list, no duplicates between the two arrays.
- whyGoodMatch: 2-4 short bullet points, each written directly to the candidate ("you"), citing concrete evidence from the candidate profile.
- whyNotPerfectMatch: 2-4 short bullet points explaining honestly what keeps this from being a perfect match. If matchPercent is very high, it is fine for this to focus on minor gaps.
- improvementSuggestions: 2-4 concrete, actionable bullet points on what the candidate could do to become a stronger fit for this exact job.
- recommendation: 2-3 sentences giving a clear, personal apply-or-not recommendation with reasoning, written directly to the candidate ("you").
- shouldApply: true if you would recommend applying despite any gaps, false only if the mismatch is severe.
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
        String description = job.getDescription();
        if (description != null && description.length() > 1500) {
            description = description.substring(0, 1500);
        }

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

    private String buildCandidateProfileBlock(CVAnalysis analysis) {
        return """
Field: %s
Skills: %s
Experience summary: %s
Strengths: %s
Missing skills: %s
Overall CV score: %s
""".formatted(
                nullToNA(analysis.getCandidateField()),
                nullToNA(analysis.getSkills()),
                nullToNA(analysis.getSummary()),
                nullToNA(analysis.getStrengths()),
                nullToNA(analysis.getMissingSkills()),
                nullToNA(analysis.getOverallScore())
        );
    }

    private String buildJobsBlock(List<Job> jobs) {
        StringBuilder sb = new StringBuilder();

        for (Job job : jobs) {
            String description = job.getDescription();
            if (description != null && description.length() > 500) {
                description = description.substring(0, 500);
            }

            sb.append("---\n")
                    .append("jobId: ").append(job.getId()).append("\n")
                    .append("Title: ").append(nullToNA(job.getTitle())).append("\n")
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
