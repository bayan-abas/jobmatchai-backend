package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisCacheRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.service.CVTextExtractorService;
import com.jobmatchai.backend.service.OpenAICVAnalysisService;
import com.jobmatchai.backend.service.storage.LocalFileStorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Covers the CV-upload security hardening in CVController#uploadCV: only PDF/DOCX (verified by
// actual content, not just extension), a size cap, and a fully server-generated storage filename
// - see CvFileValidator and CVTextExtractorService#detectContentType. cvTextExtractorService is
// mocked here (its real-Tika detection behavior is covered separately by
// CVTextExtractorServiceTest) so these tests focus purely on the controller's own
// validation/orchestration branching, matching this repo's existing controller-test convention
// (plain JUnit5 + Mockito + ReflectionTestUtils, no MockMvc/@SpringBootTest - see
// JobControllerTest/PaymentControllerTest).
@ExtendWith(MockitoExtension.class)
class CVControllerTest {

    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Mock
    private UserRepository userRepository;
    @Mock
    private CVTextExtractorService cvTextExtractorService;
    @Mock
    private OpenAICVAnalysisService openAICVAnalysisService;
    @Mock
    private CVAnalysisRepository cvAnalysisRepository;
    @Mock
    private CVAnalysisCacheRepository cvAnalysisCacheRepository;
    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private JobRepository jobRepository;
    @Mock
    private Authentication authentication;

    @TempDir
    Path uploadDir;

    private CVController cvController;

    @BeforeEach
    void setUp() {
        cvController = new CVController();
        ReflectionTestUtils.setField(cvController, "userRepository", userRepository);
        ReflectionTestUtils.setField(cvController, "cvTextExtractorService", cvTextExtractorService);
        ReflectionTestUtils.setField(cvController, "openAICVAnalysisService", openAICVAnalysisService);
        ReflectionTestUtils.setField(cvController, "cvAnalysisRepository", cvAnalysisRepository);
        ReflectionTestUtils.setField(cvController, "cvAnalysisCacheRepository", cvAnalysisCacheRepository);
        ReflectionTestUtils.setField(cvController, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(cvController, "jobRepository", jobRepository);

        // Real LocalFileStorageService pointed at the @TempDir, not a mock - these tests assert
        // against actual files on disk (see fileCountInUploadDir/Files.exists below), and this
        // preserves that. Absolute path - Path.resolve (used inside LocalFileStorageService)
        // returns an absolute uploadDir as-is regardless of the test JVM's working directory.
        LocalFileStorageService localFileStorageService = new LocalFileStorageService();
        ReflectionTestUtils.setField(localFileStorageService, "uploadDir", uploadDir.toString());
        ReflectionTestUtils.setField(cvController, "fileStorageService", localFileStorageService);

        ReflectionTestUtils.setField(cvController, "maxCvUploadSizeBytes", 10L * 1024 * 1024);
    }

    private static User candidateUser(String email) {
        User user = new User();
        user.setEmail(email);
        user.setRole("candidate");
        return user;
    }

    private void mockAuthenticatedUser(String email, User user) {
        when(authentication.getName()).thenReturn(email);
        when(userRepository.findByEmail(email)).thenReturn(user);
    }

    private static Map<?, ?> bodyOf(ResponseEntity<?> response) {
        return (Map<?, ?>) response.getBody();
    }

    private long fileCountInUploadDir() throws IOException {
        try (var stream = Files.list(uploadDir)) {
            return stream.count();
        }
    }

    // ---- valid uploads ----

    @Test
    void uploadCV_validPdf_savesFileWithServerGeneratedNameAndReturnsExpectedShape() throws Exception {
        User user = candidateUser("candidate@example.com");
        mockAuthenticatedUser("candidate@example.com", user);

        MockMultipartFile file = new MockMultipartFile(
                "file", "my-resume.pdf", "application/pdf", "irrelevant-mocked-content".getBytes());

        when(cvTextExtractorService.detectContentType(any())).thenReturn(PDF_CONTENT_TYPE);
        when(cvTextExtractorService.extractText(any())).thenReturn("Jane Doe - Software Engineer - 5 years experience");
        when(openAICVAnalysisService.validateCV(anyString(), anyString()))
                .thenReturn("{\"isCV\":true,\"confidence\":90}");

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = bodyOf(response);
        assertThat(body.get("originalFileName")).isEqualTo("my-resume.pdf");

        String storedFileName = (String) body.get("fileName");
        assertThat(storedFileName).isNotNull().endsWith(".pdf");
        // Server-generated, not derived from the uploaded name - the whole point of the hardening.
        assertThat(storedFileName).isNotEqualTo("my-resume.pdf").doesNotContain("my-resume");
        assertThat(Files.exists(uploadDir.resolve(storedFileName))).isTrue();

        assertThat(user.getCvFileName()).isEqualTo(storedFileName);
        assertThat(user.getOriginalCvFileName()).isEqualTo("my-resume.pdf");
        verify(userRepository).save(user);
    }

    @Test
    void uploadCV_validDocx_savesFileWithServerGeneratedNameAndReturnsExpectedShape() throws Exception {
        User user = candidateUser("candidate2@example.com");
        mockAuthenticatedUser("candidate2@example.com", user);

        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.docx", DOCX_CONTENT_TYPE, "irrelevant-mocked-content".getBytes());

        when(cvTextExtractorService.detectContentType(any())).thenReturn(DOCX_CONTENT_TYPE);
        when(cvTextExtractorService.extractText(any())).thenReturn("John Smith - Product Manager - 8 years experience");
        when(openAICVAnalysisService.validateCV(anyString(), anyString()))
                .thenReturn("{\"isCV\":true,\"confidence\":90}");

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> body = bodyOf(response);
        assertThat(body.get("originalFileName")).isEqualTo("resume.docx");

        String storedFileName = (String) body.get("fileName");
        assertThat(storedFileName).isNotNull().endsWith(".docx");
        assertThat(storedFileName).isNotEqualTo("resume.docx");
        assertThat(Files.exists(uploadDir.resolve(storedFileName))).isTrue();
    }

    // ---- invalid file type ----

    @Test
    void uploadCV_invalidFileType_rejectedBeforeTouchingContentOrDisk() throws Exception {
        User user = candidateUser("candidate3@example.com");
        mockAuthenticatedUser("candidate3@example.com", user);

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "just some notes".getBytes());

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(response).get("success")).isEqualTo(false);
        assertThat(bodyOf(response).get("message")).isEqualTo("Only PDF and DOCX files are allowed.");

        // Rejected on extension alone - no need to even inspect content, and nothing written.
        verify(cvTextExtractorService, never()).detectContentType(any());
        verify(userRepository, never()).save(any());
        assertThat(fileCountInUploadDir()).isZero();
    }

    // ---- oversized file ----

    @Test
    void uploadCV_oversizedFile_rejectedBeforeContentCheckOrDisk() throws Exception {
        User user = candidateUser("candidate4@example.com");
        mockAuthenticatedUser("candidate4@example.com", user);

        ReflectionTestUtils.setField(cvController, "maxCvUploadSizeBytes", 10L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "big-resume.pdf", "application/pdf", "this content is longer than ten bytes".getBytes());

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(response).get("message")).asString().contains("maximum allowed size");

        verify(cvTextExtractorService, never()).detectContentType(any());
        verify(userRepository, never()).save(any());
        assertThat(fileCountInUploadDir()).isZero();
    }

    // ---- fake extension (executable renamed to .pdf) ----

    @Test
    void uploadCV_executableRenamedAsPdf_rejectedAndNeverWrittenToDisk() throws Exception {
        User user = candidateUser("candidate5@example.com");
        mockAuthenticatedUser("candidate5@example.com", user);

        byte[] exeBytes = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file", "totally-a-resume.pdf", "application/pdf", exeBytes);

        // Simulates what CVTextExtractorServiceTest proves real Tika actually returns for this
        // byte content - here we're testing the controller's reaction to a mismatch, not Tika
        // itself again.
        when(cvTextExtractorService.detectContentType(any())).thenReturn("application/x-msdownload");

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bodyOf(response).get("message")).asString().contains("does not match a valid PDF or DOCX document");

        verify(cvTextExtractorService, never()).extractText(any());
        verify(openAICVAnalysisService, never()).validateCV(anyString(), anyString());
        verify(userRepository, never()).save(any());
        assertThat(fileCountInUploadDir()).isZero();
    }

    // ---- existing behavior preserved ----

    @Test
    void uploadCV_notActuallyACv_stillDeletesFileAndReturnsBadRequest() throws Exception {
        User user = candidateUser("candidate6@example.com");
        mockAuthenticatedUser("candidate6@example.com", user);

        MockMultipartFile file = new MockMultipartFile(
                "file", "random.pdf", "application/pdf", "irrelevant-mocked-content".getBytes());

        when(cvTextExtractorService.detectContentType(any())).thenReturn(PDF_CONTENT_TYPE);
        when(cvTextExtractorService.extractText(any())).thenReturn("Some unrelated document text.");
        when(openAICVAnalysisService.validateCV(anyString(), anyString()))
                .thenReturn("{\"isCV\":false,\"confidence\":10,\"reason\":\"Not a CV\"}");

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(userRepository, never()).save(any());
        // The passing-content-check file gets written, then must be cleaned up once the AI
        // classifier rejects it - unchanged from before this hardening.
        assertThat(fileCountInUploadDir()).isZero();
    }

    @Test
    void uploadCV_replacingExistingCv_deletesPreviousFile() throws Exception {
        String previousFileName = "old-stored-name.pdf";
        Files.writeString(uploadDir.resolve(previousFileName), "old content", StandardCharsets.UTF_8);

        User user = candidateUser("candidate7@example.com");
        user.setCvFileName(previousFileName);
        mockAuthenticatedUser("candidate7@example.com", user);

        MockMultipartFile file = new MockMultipartFile(
                "file", "new-resume.pdf", "application/pdf", "irrelevant-mocked-content".getBytes());

        when(cvTextExtractorService.detectContentType(any())).thenReturn(PDF_CONTENT_TYPE);
        when(cvTextExtractorService.extractText(any())).thenReturn("Fresh CV content with enough text.");
        when(openAICVAnalysisService.validateCV(anyString(), anyString()))
                .thenReturn("{\"isCV\":true,\"confidence\":90}");

        ResponseEntity<?> response = cvController.uploadCV(file, "en", authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Files.exists(uploadDir.resolve(previousFileName))).isFalse();
        assertThat(fileCountInUploadDir()).isEqualTo(1);
    }
}
