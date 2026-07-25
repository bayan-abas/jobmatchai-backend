package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.CVAnalysisCache;
import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisCacheRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryNarrativeRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.JobMatchNarrativeRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CVTextExtractorService;
import com.jobmatchai.backend.service.OpenAICVAnalysisService;
import com.jobmatchai.backend.service.storage.FileStorageService;
import com.jobmatchai.backend.util.CvFileValidator;
import com.jobmatchai.backend.util.HashUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private JobMatchNarrativeRepository jobMatchNarrativeRepository;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    @Autowired
    private CandidateAiSummaryNarrativeRepository candidateAiSummaryNarrativeRepository;

    @Autowired
    private FileStorageService fileStorageService;

    private static final String ANALYSIS_PROMPT_VERSION = "v3-structured-evidence";

    @Value("${app.cv.upload.max-size-bytes:10485760}")
    private long maxCvUploadSizeBytes;

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

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    private static String previewOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 150 ? singleLine.substring(0, 150) + "..." : singleLine;
    }

    // מעלה קובץ קורות חיים חדש, מוודא שהוא תקין ושמדובר באמת ב-CV, ושומר אותו במקום קבצי המשתמש
    @PostMapping("/upload")
    public ResponseEntity<?> uploadCV(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "language", defaultValue = "en") String language,
            Authentication authentication) {

        try {
            String resolvedEmail = authentication.getName();

            User user = userRepository.findByEmail(resolvedEmail);

            if (user == null) {
                return badRequest("User not found");
            }

            String originalFileName = file.getOriginalFilename();

            if (originalFileName == null || originalFileName.isBlank()) {
                return badRequest(pickByLanguage(language,
                        "Invalid file name.",
                        "اسم الملف غير صالح.",
                        "שם קובץ לא תקין."));
            }

            String extension = CvFileValidator.extensionOf(originalFileName);

            if (extension == null) {
                return badRequest(pickByLanguage(language,
                        "Only PDF and DOCX files are allowed.",
                        "يُسمح فقط بملفات PDF وDOCX.",
                        "מותרים רק קבצי PDF ו-DOCX."));
            }

            if (file.isEmpty()) {
                return badRequest(pickByLanguage(language,
                        "The uploaded file is empty.",
                        "الملف الذي تم رفعه فارغ.",
                        "הקובץ שהועלה ריק."));
            }

            if (file.getSize() > maxCvUploadSizeBytes) {
                String maxSizeMb = String.valueOf(maxCvUploadSizeBytes / (1024 * 1024));
                return badRequest(pickByLanguage(language,
                        "File exceeds the maximum allowed size of " + maxSizeMb + "MB.",
                        "حجم الملف يتجاوز الحد الأقصى المسموح به وهو " + maxSizeMb + " ميجابايت.",
                        "הקובץ חורג מהגודל המרבי המותר של " + maxSizeMb + "MB."));
            }

            String detectedContentType;
            try (InputStream fileStream = file.getInputStream()) {
                detectedContentType = cvTextExtractorService.detectContentType(fileStream);
            } catch (IOException readException) {
                return badRequest(pickByLanguage(language,
                        "The uploaded file could not be read.",
                        "تعذر قراءة الملف الذي تم رفعه.",
                        "לא ניתן היה לקרוא את הקובץ שהועלה."));
            }

            if (!CvFileValidator.contentMatchesExtension(extension, detectedContentType)) {
                return badRequest(pickByLanguage(language,
                        "The uploaded file's content does not match a valid PDF or DOCX document.",
                        "محتوى الملف الذي تم رفعه لا يتطابق مع مستند PDF أو DOCX صالح.",
                        "תוכן הקובץ שהועלה אינו תואם למסמך PDF או DOCX תקין."));
            }

            String fileName = UUID.randomUUID() + "." + extension;

            File tempFile = File.createTempFile("cv-upload-", "." + extension);
            try {
                file.transferTo(tempFile);

                String extractedText;
                try {
                    extractedText = cvTextExtractorService.extractText(tempFile);
                } catch (Exception extractException) {
                    log.warn("CV upload rejected - file could not be parsed as a valid {} document: user={} file={}",
                            extension.toUpperCase(), resolvedEmail, originalFileName, extractException);
                    return badRequest(pickByLanguage(language,
                            "The uploaded file could not be processed - it may be corrupted, password-protected, or in an unsupported format.",
                            "تعذّرت معالجة الملف الذي تم رفعه - قد يكون تالفًا أو محميًا بكلمة مرور أو بصيغة غير مدعومة.",
                            "לא ניתן היה לעבד את הקובץ שהועלה - ייתכן שהוא פגום, מוגן בסיסמה, או בפורמט לא נתמך."));
                }

                log.info("CV upload text extraction: user={} file={} extractedLength={} preview=\"{}\"",
                        resolvedEmail, originalFileName,
                        extractedText == null ? 0 : extractedText.length(),
                        previewOf(extractedText));

                if (extractedText == null || extractedText.isBlank()) {
                    return badRequest(pickByLanguage(language,
                            "No readable text was found in this file - it looks like a scanned or image-only document with no real text layer. Please upload a text-based PDF or DOCX, not a photo or scanned copy.",
                            "لم يتم العثور على نص قابل للقراءة في هذا الملف - يبدو أنه مستند ممسوح ضوئيًا أو يعتمد على صور فقط بدون طبقة نص حقيقية. يرجى رفع ملف PDF أو DOCX نصي، وليس صورة أو نسخة ممسوحة ضوئيًا.",
                            "לא נמצא טקסט קריא בקובץ זה - נראה שמדובר במסמך סרוק או מבוסס תמונה בלבד, ללא שכבת טקסט אמיתית. אנא העלה קובץ PDF או DOCX המבוסס על טקסט, ולא צילום או סריקה."));
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
                    return badRequest(pickByLanguage(language,
                            "We were able to read this document, but it doesn't look like a CV/resume: ",
                            "تمكّنا من قراءة هذا المستند، لكنه لا يبدو سيرة ذاتية: ",
                            "הצלחנו לקרוא את המסמך הזה, אך הוא אינו נראה כקורות חיים: ") + reason);
                }

                fileStorageService.store(tempFile, fileName);
            } finally {
                tempFile.delete();
            }

            String previousFileName = user.getCvFileName();
            if (previousFileName != null && !previousFileName.isBlank() && !previousFileName.equals(fileName)) {
                fileStorageService.delete(previousFileName);
            }

            user.setCvFileName(fileName);
            user.setOriginalCvFileName(originalFileName);
            userRepository.save(user);

            return ResponseEntity.ok(Map.of("fileName", fileName, "originalFileName", originalFileName));

        } catch (Exception e) {
            log.error("Failed to upload CV", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "message", "Failed to upload CV. Please try again."));
        }
    }

    // מוחק את קובץ קורות החיים של המשתמש וכל נתוני ההתאמה שהתבססו עליו
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
                fileStorageService.delete(fileName);
            }

            user.setCvFileName(null);
            user.setOriginalCvFileName(null);
            userRepository.save(user);

            cvAnalysisRepository.findByUserEmail(email)
                    .ifPresent(cvAnalysisRepository::delete);

            jobMatchScoreRepository.deleteByCandidateEmail(email);
            jobMatchNarrativeRepository.deleteByCandidateEmail(email);
            matchScoreJobRepository.deleteByCandidateEmail(email);

            return ResponseEntity.ok("CV deleted successfully");

        } catch (Exception e) {
            log.error("Failed to delete CV", e);

            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
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

    // מאפשר למשתמש להוריד את קובץ קורות החיים שלו עצמו בלבד
    @GetMapping("/download/{fileName}")
    public ResponseEntity<?> downloadCV(@PathVariable String fileName, Authentication authentication) {

        try {

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

    // מאפשר לחברה להוריד את קורות החיים של מועמד שהגיש בקשה לאחת ממשרותיה
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

    private ResponseEntity<?> serveCvFile(String fileName, String originalFileName) throws Exception {
        if (!fileStorageService.exists(fileName)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = fileStorageService.loadAsResource(fileName);
        String lowerFileName = fileName.toLowerCase();

        String displayFilename = originalFileName != null && !originalFileName.isBlank()
                ? originalFileName
                : fileName;

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

    // מחלץ ומחזיר את הטקסט הגולמי מתוך קובץ קורות החיים השמור של המשתמש
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

            if (!fileStorageService.exists(fileName)) {
                return ResponseEntity.notFound().build();
            }

            String text = fileStorageService.withLocalFile(fileName, cvTextExtractorService::extractText);

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

    // מריץ ניתוח AI מלא על קורות החיים (עם קאש לפי hash של הטקסט) ושומר את התוצאה המובנית
    @Transactional
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

            if (!fileStorageService.exists(fileName)) {
                return ResponseEntity.notFound().build();
            }

            String text = fileStorageService.withLocalFile(fileName, cvTextExtractorService::extractText);

            if (text == null || text.isBlank()) {
                return ResponseEntity.badRequest().body("Could not extract text from this CV");
            }

            String cvTextHash = HashUtil.sha256(text + "|" + language);

            CVAnalysis analysis = cvAnalysisRepository
                    .findByUserEmail(email)
                    .orElse(new CVAnalysis());

            if (cvTextHash.equals(analysis.getCvTextHash())) {
                return ResponseEntity.ok(analysis);
            }

            // הקאש הוא לפי hash של הטקסט - שני משתמשים עם אותו CV בדיוק (או אותו משתמש שמעלה שוב) חוסכים קריאת AI
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
                discardStaleMatchScores(email);

                return ResponseEntity.ok(analysis);
            }

            String aiResult = openAICVAnalysisService.analyzeCV(text, language);
            JsonNode json = objectMapper.readTree(aiResult);

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
            discardStaleMatchScores(email);

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

            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return ResponseEntity.internalServerError()
                    .body("Failed to analyze CV. Please try again.");
        }
    }

    // מנקה ציוני התאמה ותקצירים ישנים כי הם התבססו על ה-CV הקודם ואינם רלוונטיים יותר
    private void discardStaleMatchScores(String email) {
        jobMatchScoreRepository.deleteByCandidateEmail(email);
        jobMatchNarrativeRepository.deleteByCandidateEmail(email);
        matchScoreJobRepository.deleteByCandidateEmail(email);
        candidateAiSummaryRepository.deleteByCandidateEmail(email);
        candidateAiSummaryNarrativeRepository.deleteByCandidateEmail(email);
    }

    // מחזיר את תוצאת ניתוח ה-AI האחרונה שנשמרה עבור קורות החיים של המשתמש
    @GetMapping("/analysis")
    public ResponseEntity<?> getCVAnalysis(Authentication authentication) {
        String email = authentication.getName();
        return cvAnalysisRepository.findByUserEmail(email)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("hasAnalysis", false)));
    }
}
