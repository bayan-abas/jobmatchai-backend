package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.CVAnalysisCache;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisCacheRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CVTextExtractorService;
import com.jobmatchai.backend.service.OpenAICVAnalysisService;
import com.jobmatchai.backend.util.HashUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/cv")
public class CVController {

    private static final Logger log = LoggerFactory.getLogger(CVController.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CVTextExtractorService cvTextExtractorService;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private CVAnalysisCacheRepository cvAnalysisCacheRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    // Bump this whenever the analyzeCV prompt changes materially (mirrors JobMatchService's
    // MATCH_SCHEMA_VERSION pattern) - keeps a prompt improvement from being silently masked by
    // stale cached results for CV content that was already analyzed under the old prompt.
    // v3 added the structured evidence fields (professionTitle, educationEvidence,
    // certificationsEvidence, licensesEvidence, yearsOfExperience, technicalSkills, softSkills,
    // languages, previousJobTitles) the job matcher now relies on instead of free-text prose -
    // any CV analyzed under v2 has all of those as null/blank, so it must be recomputed.
    private static final String ANALYSIS_PROMPT_VERSION = "v3-structured-evidence";

    @Value("${app.upload.dir:uploads/cvs/}")
    private String uploadDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/test")
    public String test() {
        return "CV API is working";
    }

    private String pickByLanguage(String language, String en, String ar, String he) {
        return switch (language == null ? "en" : language) {
            case "ar" -> ar;
            case "he" -> he;
            default -> en;
        };
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadCV(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "en") String language,
            Authentication authentication) {

        try {
            String resolvedEmail = authentication.getName();

            User user = userRepository.findByEmail(resolvedEmail);

            if (user == null) {
                return ResponseEntity.badRequest().body("User not found");
            }

            // Path.resolve (unlike the legacy File(parent, child) constructor) correctly
            // returns uploadDir as-is when it's already absolute - which it is by default
            // now that it points outside the project directory - instead of risking it
            // getting silently mis-joined with the CWD.
            File folder = Paths.get(System.getProperty("user.dir")).resolve(uploadDir).normalize().toFile();

            if (!folder.exists()) {
                folder.mkdirs();
            }

            String originalFileName = file.getOriginalFilename();

            if (originalFileName == null || originalFileName.isBlank()) {
                return ResponseEntity.badRequest().body("Invalid file name");
            }

            String lowerFileName = originalFileName.toLowerCase();

            if (!lowerFileName.endsWith(".pdf")
                    && !lowerFileName.endsWith(".doc")
                    && !lowerFileName.endsWith(".docx")) {
                return ResponseEntity.badRequest().body("Only PDF, DOC, and DOCX files are allowed");
            }

            String safeFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");
            String fileName = System.currentTimeMillis() + "_" + safeFileName;

            File destination = new File(folder, fileName);
            file.transferTo(destination);

            String extractedText;
            try {
                extractedText = cvTextExtractorService.extractText(destination);
            } catch (Exception extractException) {
                // A file Tika can't parse (corrupt, or a renamed non-document binary) would
                // otherwise be written to disk and left there forever, unassociated with any
                // user - clean it up the same as the "no readable text" case below.
                destination.delete();
                return ResponseEntity.badRequest()
                        .body(pickByLanguage(language,
                                "The uploaded file does not contain readable CV text.",
                                "الملف الذي تم رفعه لا يحتوي على نص سيرة ذاتية قابل للقراءة.",
                                "הקובץ שהועלה אינו מכיל טקסט קורות חיים קריא."));
            }

            if (extractedText == null || extractedText.isBlank()) {
                destination.delete();
                return ResponseEntity.badRequest()
                        .body(pickByLanguage(language,
                                "The uploaded file does not contain readable CV text.",
                                "الملف الذي تم رفعه لا يحتوي على نص سيرة ذاتية قابل للقراءة.",
                                "הקובץ שהועלה אינו מכיל טקסט קורות חיים קריא."));
            }

            String validationResult = openAICVAnalysisService.validateCV(extractedText, language);
            JsonNode validationJson = objectMapper.readTree(validationResult);

            boolean isCV = validationJson.path("isCV").asBoolean(false);
            int confidence = validationJson.path("confidence").asInt(0);
            String reason = validationJson.path("reason").asText(pickByLanguage(language,
                    "The uploaded file is not a valid CV.",
                    "الملف الذي تم رفعه ليس سيرة ذاتية صالحة.",
                    "הקובץ שהועלה אינו קורות חיים תקינים."));

            if (!isCV || confidence < 75) {
                destination.delete();
                return ResponseEntity.badRequest()
                        .body(pickByLanguage(language,
                                "Invalid CV file: ",
                                "ملف السيرة الذاتية غير صالح: ",
                                "קובץ קורות החיים אינו תקין: ") + reason);
            }

            // Drop the previous CV file now that the new one is validated and about to
            // replace it - otherwise every re-upload leaves the old file on disk forever.
            String previousFileName = user.getCvFileName();
            if (previousFileName != null && !previousFileName.isBlank() && !previousFileName.equals(fileName)) {
                new File(folder, previousFileName).delete();
            }

            user.setCvFileName(fileName);
            user.setOriginalCvFileName(originalFileName);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("fileName", fileName, "originalFileName", originalFileName));

        } catch (Exception e) {
            log.error("Failed to upload CV", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to upload CV. Please try again.");
        }
    }

    // Transactional so the user-record update and the CVAnalysis delete either both
    // commit or both roll back - otherwise a failure between the two could clear
    // cvFileName while leaving a stale CVAnalysis row behind, letting match scores
    // keep showing for a candidate who believes they have no CV on file.
    @Transactional
    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteCV(Authentication authentication) {
        try {
            String email = authentication.getName();
            User user = userRepository.findByEmail(email);

            if (user == null) {
                return ResponseEntity.badRequest().body("User not found");
            }

            String fileName = user.getCvFileName();

            if (fileName != null && !fileName.isBlank()) {
                Path uploadPath = Paths.get(System.getProperty("user.dir"))
                        .resolve(uploadDir)
                        .normalize()
                        .toAbsolutePath();

                Path filePath = uploadPath.resolve(fileName)
                        .normalize()
                        .toAbsolutePath();

                if (filePath.startsWith(uploadPath)) {
                    File cvFile = filePath.toFile();

                    if (cvFile.exists()) {
                        cvFile.delete();
                    }
                }
            }

            user.setCvFileName(null);
            user.setOriginalCvFileName(null);
            userRepository.save(user);

            cvAnalysisRepository.findByUserEmail(email)
                    .ifPresent(cvAnalysisRepository::delete);

            return ResponseEntity.ok("CV deleted successfully");

        } catch (Exception e) {
            log.error("Failed to delete CV", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to delete CV. Please try again.");
        }
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentCV(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        if (user.getCvFileName() == null || user.getCvFileName().isEmpty()) {
            return ResponseEntity.ok("");
        }

        return ResponseEntity.ok(user.getCvFileName());
    }

    // Storage key (for building the download URL) and the original upload name
    // (for display) together, since /current alone only ever exposed the storage key.
    @GetMapping("/current-info")
    public ResponseEntity<?> getCurrentCVInfo(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        String fileName = user.getCvFileName();

        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.ok(Map.of("fileName", "", "originalFileName", ""));
        }

        String originalFileName = user.getOriginalCvFileName();

        return ResponseEntity.ok(Map.of(
                "fileName", fileName,
                "originalFileName", originalFileName != null && !originalFileName.isBlank() ? originalFileName : fileName));
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<?> downloadCV(@PathVariable String fileName, Authentication authentication) {

        try {
            // This only ever needs to serve the requesting candidate's own CV (no other
            // part of the app - company or otherwise - links to this endpoint), so require
            // the file to actually belong to them. Without this, any authenticated user
            // could download any other candidate's CV just by guessing/enumerating the
            // "{timestamp}_{name}" storage filename.
            User owner = userRepository.findByEmail(authentication.getName());
            if (owner == null || !fileName.equals(owner.getCvFileName())) {
                return ResponseEntity.status(403).body("You do not have access to this file");
            }

            return serveCvFile(fileName, owner.getOriginalCvFileName());

        } catch (Exception e) {
            log.error("Failed to download CV fileName={}", fileName, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to download CV. Please try again.");
        }
    }

    // Company-side equivalent of downloadCV above - scoped by applicationId (never a raw
    // filename, so there's no filename to guess/enumerate) and ownership-checked the same way
    // every other company endpoint in this app checks an application: the requester must be the
    // company that owns the job the application was submitted to.
    @GetMapping("/company-download/{applicationId}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<?> downloadCandidateResume(@PathVariable Long applicationId, Authentication authentication) {

        try {
            Application application = applicationRepository.findById(applicationId).orElse(null);

            if (application == null) {
                return ResponseEntity.status(404).body("Application not found");
            }

            Job job = jobRepository.findById(application.getJobId()).orElse(null);

            if (job == null || !authentication.getName().equals(job.getCompanyEmail())) {
                return ResponseEntity.status(404).body("Application not found");
            }

            User candidate = userRepository.findByEmail(application.getCandidateEmail());

            if (candidate == null || candidate.getCvFileName() == null || candidate.getCvFileName().isBlank()) {
                return ResponseEntity.status(404).body("This candidate has no resume on file");
            }

            return serveCvFile(candidate.getCvFileName(), candidate.getOriginalCvFileName());

        } catch (Exception e) {
            log.error("Failed to download candidate resume applicationId={}", applicationId, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to download resume. Please try again.");
        }
    }

    // Shared by downloadCV (candidate downloading their own resume) and
    // downloadCandidateResume (company downloading an applicant's resume) - both already
    // verify the caller is entitled to fileName before calling this; it only resolves the
    // path safely and picks the right content type/display name.
    private ResponseEntity<?> serveCvFile(String fileName, String originalFileName) throws Exception {
        Path uploadPath = Paths.get(System.getProperty("user.dir"))
                .resolve(uploadDir)
                .normalize()
                .toAbsolutePath();

        Path filePath = uploadPath.resolve(fileName).normalize().toAbsolutePath();

        if (!filePath.startsWith(uploadPath)) {
            return ResponseEntity.badRequest().body("Invalid file name");
        }

        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        String resourceFilename = resource.getFilename();
        String lowerFileName = resourceFilename == null ? "" : resourceFilename.toLowerCase();

        // Prefer the name actually uploaded over the sanitized/timestamp-prefixed storage
        // name, so the browser's save dialog and inline viewer both show the real file name
        // instead of a generic one.
        String displayFilename = originalFileName != null && !originalFileName.isBlank()
                ? originalFileName
                : resourceFilename;

        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;

        if (lowerFileName.endsWith(".pdf")) {
            mediaType = MediaType.APPLICATION_PDF;
        } else if (lowerFileName.endsWith(".doc")) {
            mediaType = MediaType.parseMediaType("application/msword");
        } else if (lowerFileName.endsWith(".docx")) {
            mediaType = MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + (displayFilename != null ? displayFilename : fileName) + "\"")
                .body(resource);
    }

    @GetMapping({"/extract", "/extract-text"})
    public ResponseEntity<?> extractCVText(Authentication authentication) {
        String email = authentication.getName();

        try {
            User user = userRepository.findByEmail(email);

            if (user == null) {
                return ResponseEntity.badRequest().body("User not found");
            }

            String fileName = user.getCvFileName();

            if (fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body("No CV uploaded for user");
            }

            File cvFile = Paths.get(System.getProperty("user.dir"))
                    .resolve(uploadDir)
                    .resolve(fileName)
                    .normalize()
                    .toFile();

            if (!cvFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            String text = cvTextExtractorService.extractText(cvFile);

            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body("Could not extract text from this CV");
            }

            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(text);

        } catch (Exception e) {
            log.error("Failed to extract CV text for user={}", email, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to extract CV text. Please try again.");
        }
    }

    @PostMapping({"/analyze", "/analyze/"})
    public ResponseEntity<?> analyzeCV(
            @RequestParam(value = "language", defaultValue = "en") String language,
            Authentication authentication) {

        String email = authentication.getName();

        try {
            User user = userRepository.findByEmail(email);

            if (user == null) {
                return ResponseEntity.badRequest().body("User not found");
            }

            String fileName = user.getCvFileName();

            if (fileName == null || fileName.isBlank()) {
                return ResponseEntity.badRequest().body("No CV uploaded for user");
            }

            File cvFile = Paths.get(System.getProperty("user.dir"))
                    .resolve(uploadDir)
                    .resolve(fileName)
                    .normalize()
                    .toFile();

            if (!cvFile.exists()) {
                return ResponseEntity.notFound().build();
            }

            String text = cvTextExtractorService.extractText(cvFile);

            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body("Could not extract text from this CV");
            }

            // Fingerprint includes language so re-analyzing in a different language still
            // regenerates wording, but re-running with the same CV + language is a no-op -
            // this is what keeps the score stable instead of drifting across repeated calls.
            String cvTextHash = HashUtil.sha256(text + "|" + language);

            CVAnalysis analysis = cvAnalysisRepository
                    .findByUserEmail(email)
                    .orElse(new CVAnalysis());

            if (cvTextHash.equals(analysis.getCvTextHash())) {
                return ResponseEntity.ok(analysis);
            }

            // Durable, content-addressable cache that survives CV deletion (unlike the
            // per-user CVAnalysis row above, which deleteCV removes entirely). The exact same
            // file re-uploaded after a delete hashes to the exact same value, so a hit here
            // gives byte-identical results instead of relying on the AI to reproduce the same
            // output for the same input - which the OpenAI Responses API this app uses does
            // not guarantee (it has no seed parameter, unlike the Chat Completions API).
            Optional<CVAnalysisCache> cached = cvAnalysisCacheRepository
                    .findByCvTextHashAndLanguageAndPromptVersion(cvTextHash, language, ANALYSIS_PROMPT_VERSION);

            if (cached.isPresent()) {
                CVAnalysisCache hit = cached.get();
                analysis.setUserEmail(email);
                analysis.setCandidateField(hit.getCandidateField());
                analysis.setProfessionTitle(hit.getProfessionTitle());
                analysis.setSkills(hit.getSkills());
                analysis.setTechnicalSkills(hit.getTechnicalSkills());
                analysis.setSoftSkills(hit.getSoftSkills());
                analysis.setLanguages(hit.getLanguages());
                analysis.setPreviousJobTitles(hit.getPreviousJobTitles());
                analysis.setSummary(hit.getSummary());
                analysis.setStrengths(hit.getStrengths());
                analysis.setMissingSkills(hit.getMissingSkills());
                analysis.setRecommendedRoles(hit.getRecommendedRoles());
                analysis.setOverallScore(hit.getOverallScore());
                analysis.setEvaluationReason(hit.getEvaluationReason());
                analysis.setMissingInformation(hit.getMissingInformation());
                analysis.setEducationEvidence(hit.getEducationEvidence());
                analysis.setCertificationsEvidence(hit.getCertificationsEvidence());
                analysis.setLicensesEvidence(hit.getLicensesEvidence());
                analysis.setYearsOfExperience(hit.getYearsOfExperience());
                analysis.setExperienceLevel(hit.getExperienceLevel());
                analysis.setCvTextHash(cvTextHash);

                cvAnalysisRepository.save(analysis);

                return ResponseEntity.ok(analysis);
            }

            String aiResult = openAICVAnalysisService.analyzeCV(text, language);
            JsonNode json = objectMapper.readTree(aiResult);

            // analyzeCV() swallows OpenAI failures (timeout, rate limit, malformed response)
            // internally and returns a normally-shaped JSON with overallScore left blank and
            // the error message stuffed into "summary" - that's the only signal a failure
            // happened. Treating it as a real result would cache a fake "0%" analysis under
            // this CV's hash, and since the hash wouldn't change on retry, the user would be
            // stuck seeing that fake failure forever instead of ever getting a real score.
            if (json.path("overallScore").asText("").isBlank()) {
                String failureMessage = json.path("summary").asText("");
                return ResponseEntity.internalServerError()
                        .body(failureMessage.isBlank() ? "Failed to analyze CV. Please try again." : failureMessage);
            }

            analysis.setUserEmail(email);
            analysis.setCandidateField(json.path("candidateField").asText(""));
            analysis.setProfessionTitle(json.path("professionTitle").asText(""));
            analysis.setSkills(json.path("skills").asText(""));
            analysis.setTechnicalSkills(json.path("technicalSkills").asText(""));
            analysis.setSoftSkills(json.path("softSkills").asText(""));
            analysis.setLanguages(json.path("languages").asText(""));
            analysis.setPreviousJobTitles(json.path("previousJobTitles").asText(""));
            analysis.setSummary(json.path("summary").asText(""));
            analysis.setStrengths(json.path("strengths").asText(""));
            analysis.setMissingSkills(json.path("missingSkills").asText(""));
            analysis.setRecommendedRoles(json.path("recommendedRoles").asText(""));
            analysis.setOverallScore(json.path("overallScore").asInt());
            analysis.setEvaluationReason(json.path("evaluationReason").asText(""));
            analysis.setMissingInformation(json.path("missingInformation").asText(""));
            analysis.setEducationEvidence(json.path("educationEvidence").asText(""));
            analysis.setCertificationsEvidence(json.path("certificationsEvidence").asText(""));
            analysis.setLicensesEvidence(json.path("licensesEvidence").asText(""));
            analysis.setYearsOfExperience(json.path("yearsOfExperience").asText(""));
            analysis.setExperienceLevel(json.path("experienceLevel").asText("none"));
            analysis.setCvTextHash(cvTextHash);

            cvAnalysisRepository.save(analysis);

            // Store in the durable cache too, so the next analysis of this exact CV content -
            // by this user after a delete/re-upload, or by anyone else who uploads
            // byte-identical content - reuses this result instead of calling the AI again.
            CVAnalysisCache cacheEntry = new CVAnalysisCache();
            cacheEntry.setCvTextHash(cvTextHash);
            cacheEntry.setLanguage(language);
            cacheEntry.setPromptVersion(ANALYSIS_PROMPT_VERSION);
            cacheEntry.setCandidateField(analysis.getCandidateField());
            cacheEntry.setProfessionTitle(analysis.getProfessionTitle());
            cacheEntry.setSkills(analysis.getSkills());
            cacheEntry.setTechnicalSkills(analysis.getTechnicalSkills());
            cacheEntry.setSoftSkills(analysis.getSoftSkills());
            cacheEntry.setLanguages(analysis.getLanguages());
            cacheEntry.setPreviousJobTitles(analysis.getPreviousJobTitles());
            cacheEntry.setSummary(analysis.getSummary());
            cacheEntry.setStrengths(analysis.getStrengths());
            cacheEntry.setMissingSkills(analysis.getMissingSkills());
            cacheEntry.setRecommendedRoles(analysis.getRecommendedRoles());
            cacheEntry.setOverallScore(analysis.getOverallScore());
            cacheEntry.setEvaluationReason(analysis.getEvaluationReason());
            cacheEntry.setMissingInformation(analysis.getMissingInformation());
            cacheEntry.setEducationEvidence(analysis.getEducationEvidence());
            cacheEntry.setCertificationsEvidence(analysis.getCertificationsEvidence());
            cacheEntry.setLicensesEvidence(analysis.getLicensesEvidence());
            cacheEntry.setYearsOfExperience(analysis.getYearsOfExperience());
            cacheEntry.setExperienceLevel(analysis.getExperienceLevel());
            cvAnalysisCacheRepository.save(cacheEntry);

            return ResponseEntity.ok(analysis);

        } catch (Exception e) {
            log.error("Failed to analyze CV for user={}", email, e);
            return ResponseEntity.internalServerError()
                    .body("Failed to analyze CV. Please try again.");
        }
    }

    @GetMapping("/analysis")
    public ResponseEntity<?> getCVAnalysis(Authentication authentication) {
        String email = authentication.getName();
        return cvAnalysisRepository.findByUserEmail(email)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("hasAnalysis", false)));
    }
}
