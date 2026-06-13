package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.CVAnalysis;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CVTextExtractorService;
import com.jobmatchai.backend.service.OpenAICVAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/cv")
@CrossOrigin(origins = "http://localhost:5173")
public class CVController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CVTextExtractorService cvTextExtractorService;

    @Autowired
    private OpenAICVAnalysisService openAICVAnalysisService;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    private final String uploadDir = "uploads/cvs/";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping("/test")
    public String test() {
        return "CV API is working";
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadCV(
            @RequestParam("file") MultipartFile file,
            @RequestParam("email") String email) {

        try {
            User user = userRepository.findByEmail(email);

            if (user == null) {
                return ResponseEntity.badRequest().body("User not found");
            }

            File folder = new File(System.getProperty("user.dir"), uploadDir);

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

            String extractedText = cvTextExtractorService.extractText(destination);

            if (extractedText == null || extractedText.isBlank()) {
                destination.delete();
                return ResponseEntity.badRequest()
                        .body("The uploaded file does not contain readable CV text.");
            }

            String validationResult = openAICVAnalysisService.validateCV(extractedText);
            JsonNode validationJson = objectMapper.readTree(validationResult);

            boolean isCV = validationJson.path("isCV").asBoolean(false);
            int confidence = validationJson.path("confidence").asInt(0);
            String reason = validationJson.path("reason").asText("The uploaded file is not a valid CV.");

            if (!isCV || confidence < 75) {
                destination.delete();
                return ResponseEntity.badRequest()
                        .body("Invalid CV file: " + reason);
            }

            user.setCvFileName(fileName);
            userRepository.save(user);

            return ResponseEntity.ok(fileName);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to upload CV: " + e.getMessage());
        }
    }

    @DeleteMapping("/delete")
    public ResponseEntity<?> deleteCV(@RequestParam("email") String email) {
        try {
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
            userRepository.save(user);

            cvAnalysisRepository.findByUserEmail(email)
                    .ifPresent(cvAnalysisRepository::delete);

            return ResponseEntity.ok("CV deleted successfully");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to delete CV: " + e.getMessage());
        }
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentCV(@RequestParam("email") String email) {

        User user = userRepository.findByEmail(email);

        if (user == null) {
            return ResponseEntity.badRequest().body("User not found");
        }

        if (user.getCvFileName() == null || user.getCvFileName().isEmpty()) {
            return ResponseEntity.ok("");
        }

        return ResponseEntity.ok(user.getCvFileName());
    }

    @SuppressWarnings("null")
    @GetMapping("/download/{fileName}")
    public ResponseEntity<?> downloadCV(@PathVariable String fileName) {

        try {
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
                            "inline; filename=\"" + (resourceFilename != null ? resourceFilename : fileName) + "\"")
                    .body(resource);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to download CV: " + e.getMessage());
        }
    }

    @SuppressWarnings("null")
    @GetMapping({"/extract", "/extract-text"})
    public ResponseEntity<?> extractCVText(@RequestParam("email") String email) {

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
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to extract CV text: " + e.getMessage());
        }
    }

    @PostMapping({"/analyze", "/analyze/"})
    public ResponseEntity<?> analyzeCV(
            @RequestParam("email") String email,
            @RequestParam(value = "language", defaultValue = "en") String language) {

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

            String aiResult = openAICVAnalysisService.analyzeCV(text, language);
            JsonNode json = objectMapper.readTree(aiResult);

            CVAnalysis analysis = cvAnalysisRepository
                    .findByUserEmail(email)
                    .orElse(new CVAnalysis());

            analysis.setUserEmail(email);
            analysis.setCandidateField(json.path("candidateField").asText(""));
            analysis.setSkills(json.path("skills").asText(""));
            analysis.setSummary(json.path("summary").asText(""));
            analysis.setStrengths(json.path("strengths").asText(""));
            analysis.setMissingSkills(json.path("missingSkills").asText(""));
            analysis.setRecommendedRoles(json.path("recommendedRoles").asText(""));
            analysis.setOverallScore(json.path("overallScore").asText(""));
            analysis.setScoreLevel(json.path("scoreLevel").asText(""));
            analysis.setEvaluationReason(json.path("evaluationReason").asText(""));
            analysis.setMissingInformation(json.path("missingInformation").asText(""));

            cvAnalysisRepository.save(analysis);

            return ResponseEntity.ok(analysis);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body("Failed to analyze CV: " + e.getMessage());
        }
    }

    @PostMapping("/analyze-test")
    public ResponseEntity<?> analyzeTest(@RequestParam("email") String email) {
        return ResponseEntity.ok("Analyze endpoint works for: " + email);
    }

    @GetMapping("/analysis")
    public ResponseEntity<?> getCVAnalysis(@RequestParam("email") String email) {
        return cvAnalysisRepository.findByUserEmail(email)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}