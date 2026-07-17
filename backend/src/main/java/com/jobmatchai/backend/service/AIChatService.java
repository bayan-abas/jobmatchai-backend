package com.jobmatchai.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AIChatService {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.model}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.openai.com")
            .build();

    public String chat(String userMessage, String language, String contextBlock, String mode, List<Map<String, String>> history) {
        try {
            String languageInstruction = switch (language) {
                case "ar" -> "You MUST reply in Arabic only. Every single word of your response must be in Arabic. Keep job titles, company names, and skill names as-is (do not translate them).";
                case "he" -> "You MUST reply in Hebrew only. Every single word of your response must be in Hebrew. Keep job titles, company names, and skill names as-is (do not translate them).";
                default -> "You MUST reply in English only.";
            };

            String personaAndCapabilities = switch (mode == null ? "anonymous" : mode) {
                case "candidate" -> """
You are JobMatchAI's intelligent career assistant, helping a job-seeking candidate.
You can: explain their CV analysis in simple terms; explain why they got a specific match percentage for a job and what it means; show their strengths and weaknesses; explain which skills matched and which are missing for a job; suggest concrete CV improvements; recommend the most suitable jobs for them; compare two or more jobs and say which fits better and why; explain a job's responsibilities, requirements, and expectations; generate personalized interview questions and preparation tips for a specific job; generate a personalized cover letter for a specific job; recommend courses/certifications/learning resources for missing skills; do a skill-gap analysis; suggest a career roadmap (skills/technologies/certifications to learn next); answer questions about their applications (status, applied jobs, match results).
""";
                case "company" -> """
You are JobMatchAI's intelligent hiring assistant, helping a company evaluate candidates for their job postings.
You can: explain why candidates are ranked in a particular order (based on their match percentages); compare multiple candidates and explain which is the best fit and why; summarize each candidate's CV and AI analysis; highlight each candidate's strengths and weaknesses; identify missing skills/qualifications per candidate; suggest personalized interview questions based on the job requirements and a candidate's profile; suggest missing requirements/skills/qualifications to improve a job description; answer questions about job postings and applications.
""";
                default -> """
You are JobMatchAI's career assistant. No user is currently logged in, so you have no personalized data to work with.
Answer generally and helpfully about careers, resumes, interviews, and how JobMatchAI works, and mention that logging in unlocks personalized answers based on the user's real CV analysis, job matches, and applications.
""";
            };

            String dataFidelityRule = """
CRITICAL RULE: Only use facts found in the "DATA CONTEXT" section below. Never invent, guess, or assume any job, company, candidate, application, skill, or match percentage that is not explicitly present there. If the answer isn't in the data, say so honestly instead of making something up.
Exception: when suggesting courses, certifications, learning resources, or a career roadmap, you may use your own general professional knowledge, since the system has no course catalog — make clear these are general suggestions, not data from the platform.
""";

            // One fixed structural template for every mode - the frontend chat UI parses these
            // exact markers ("## Heading" lines, "- " bullets, "**bold**" spans) to render
            // scannable cards/badges instead of a text block, so the syntax here must stay
            // stable. Never write long paragraphs; this is a template, not a style suggestion.
            String responseFormatTemplate = """
Response format - follow this exact structure, since the UI parses these markers and renders them visually:
- Always start with a line "## Summary" followed by exactly ONE short sentence (max ~20 words) that answers the question at a glance.
- After that, add more sections only when relevant to the question - each one a line "## " followed by a short 1-3 word heading. Prefer these when they fit: Candidate, Match Score, Strengths, Missing Skills, Recommendation, Top Candidates, Comparison, Next Steps, Interview Tips. Invent a different short heading only if none of these fit. Never add a section with nothing meaningful in it.
- A "## Match Score" section must contain nothing but a bolded percentage on its own line, e.g. "**82%**" - no other text in that section.
- Every other section's content must be short bullet points, one per line starting with "- ", each a single sentence - never a paragraph.
- Bold (**like this**) every important value: names, percentages, statuses, and technology/skill names.
- No greetings, no restating the question, no meta phrases like "Based on the data provided".
- Keep the whole answer under ~200 words unless the user explicitly asks for more detail.

Example:

## Summary
**Jane Doe** is a **94%** match for Backend Engineer - a strong candidate.

## Candidate
- **Jane Doe** - 5 years experience, current title: Backend Developer.

## Match Score
**94%**

## Strengths
- Strong **Java** and **Spring Boot** experience matching the core stack.
- Proven experience with **PostgreSQL** and REST API design.

## Missing Skills
- No listed experience with **Kubernetes**.

## Recommendation
- Move forward to interview - strengths clearly outweigh the one gap.
""";

            String modeTone = switch (mode == null ? "anonymous" : mode) {
                case "company" -> """
Tone: fast, professional ATS assistant (like Greenhouse, Lever, or Ashby) - not a general chatbot. Never dump every candidate, application, or job: rank by relevance, show only the top 3-5 under one "## Top Candidates" (or similarly named) section as bullets - each bullet bolding the name, match percentage, and status, plus one short reason - then end with a plain line stating how many were shown, e.g. "Showing the top 3 candidates." Only show a candidate's full strengths/missing-skills/recommendation breakdown when the user explicitly asks about that one specific candidate.
""";
                case "candidate" -> "Tone: supportive career coach - encouraging but honest.";
                default -> "Tone: friendly and general - no personalized data is available yet.";
            };

            String dataSection = (contextBlock == null || contextBlock.isBlank())
                    ? ""
                    : "\n=== DATA CONTEXT ===\n" + contextBlock;

            String systemPrompt = personaAndCapabilities
                    + "\n"
                    + dataFidelityRule
                    + "\n"
                    + languageInstruction
                    + "\n"
                    + responseFormatTemplate
                    + "\n"
                    + modeTone
                    + dataSection;

            List<Map<String, Object>> input = new ArrayList<>();
            input.add(Map.of("role", "system", "content", systemPrompt));

            if (history != null) {
                for (Map<String, String> turn : history) {
                    String role = "assistant".equals(turn.get("role")) ? "assistant" : "user";
                    String content = turn.getOrDefault("content", "");
                    if (!content.isBlank()) {
                        input.add(Map.of("role", role, "content", content));
                    }
                }
            }

            input.add(Map.of("role", "user", "content", userMessage));

            Map<String, Object> body = Map.of(
                    "model", model,
                    "store", false,
                    "input", input
            );

            Map<String, Object> response = restClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer " + requireConfiguredApiKey())
                    .header("Content-Type", "application/json")
                    .body(Objects.requireNonNull(body))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            return extractText(response);

        } catch (Exception e) {
            return switch (language) {
                case "ar" -> "عذرًا، حدث خطأ. يرجى المحاولة مرة أخرى.";
                case "he" -> "מצטער, אירעה שגיאה. אנא נסה שנית.";
                default -> "Sorry, something went wrong. Please try again.";
            };
        }
    }

    private String extractText(Map<String, Object> response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            JsonNode root = objectMapper.readTree(json);
            JsonNode output = root.path("output");
            if (output.isArray() && !output.isEmpty()) {
                JsonNode content = output.get(0).path("content");
                if (content.isArray() && !content.isEmpty()) {
                    return content.get(0).path("text").asText("").trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String requireConfiguredApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured. Set it in your terminal environment or in a local .env file.");
        }

        return apiKey.trim();
    }
}
