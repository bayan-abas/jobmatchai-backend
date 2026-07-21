package com.jobmatchai.backend.service;

import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.model.User;
import com.jobmatchai.backend.repository.ApplicationRepository;
import com.jobmatchai.backend.repository.CVAnalysisRepository;
import com.jobmatchai.backend.repository.CandidateAiSummaryRepository;
import com.jobmatchai.backend.repository.EmailVerificationCodeRepository;
import com.jobmatchai.backend.repository.InterviewRepository;
import com.jobmatchai.backend.repository.JobMatchScoreRepository;
import com.jobmatchai.backend.repository.JobRepository;
import com.jobmatchai.backend.repository.MatchScoreJobRepository;
import com.jobmatchai.backend.repository.MessageRepository;
import com.jobmatchai.backend.repository.NotificationRepository;
import com.jobmatchai.backend.repository.PasswordResetTokenRepository;
import com.jobmatchai.backend.repository.RecentlyViewedJobRepository;
import com.jobmatchai.backend.repository.SavedJobRepository;
import com.jobmatchai.backend.repository.UserRepository;
import com.jobmatchai.backend.security.TokenRevocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;

@Service
public class UserDeletionService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CVAnalysisRepository cvAnalysisRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private SavedJobRepository savedJobRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private JobMatchScoreRepository jobMatchScoreRepository;

    @Autowired
    private MatchScoreJobRepository matchScoreJobRepository;

    @Autowired
    private CandidateAiSummaryRepository candidateAiSummaryRepository;

    @Autowired
    private InterviewRepository interviewRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private RecentlyViewedJobRepository recentlyViewedJobRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private EmailVerificationCodeRepository emailVerificationCodeRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Value("${app.upload.dir:uploads/cvs/}")
    private String uploadDir;

    @Transactional
    public void deleteUserAccount(String email) {
        User user = userRepository.findByEmail(email);

        if (user == null) {
            return;
        }

        // A token issued for this account before deletion must stop authenticating immediately,
        // not linger until it naturally expires (see TokenRevocationService).
        tokenRevocationService.revokeTokensIssuedBefore(email, Instant.now());

        deleteCvFile(user.getCvFileName());

        cvAnalysisRepository.deleteByUserEmail(email);
        applicationRepository.deleteByCandidateEmail(email);
        savedJobRepository.deleteByCandidateEmail(email);
        jobMatchScoreRepository.deleteByCandidateEmail(email);
        matchScoreJobRepository.deleteByCandidateEmail(email);
        candidateAiSummaryRepository.deleteByCandidateEmail(email);
        interviewRepository.deleteByCandidateEmail(email);
        messageRepository.deleteByRecipientEmail(email);
        recentlyViewedJobRepository.deleteByCandidateEmail(email);
        passwordResetTokenRepository.deleteByEmail(email);
        emailVerificationCodeRepository.deleteByEmail(email);

        if ("company".equalsIgnoreCase(user.getRole())) {
            List<Job> jobs = jobRepository.findByCompanyEmail(email);
            List<Long> jobIds = jobs.stream().map((Job job) -> job.getId()).toList();

            applicationRepository.deleteByCompanyEmail(email);
            interviewRepository.deleteByCompanyEmail(email);
            messageRepository.deleteBySenderEmail(email);

            if (!jobIds.isEmpty()) {
                savedJobRepository.deleteByJobIdInAndJobType(jobIds, "internal");
                jobMatchScoreRepository.deleteByJobIdIn(jobIds);
                matchScoreJobRepository.deleteByJobIdInAndJobType(jobIds, "internal");
                candidateAiSummaryRepository.deleteByJobIdIn(jobIds);
                recentlyViewedJobRepository.deleteByJobIdInAndJobType(jobIds, "internal");
            }

            jobRepository.deleteByCompanyEmail(email);
        }

        notificationRepository.deleteByRecipientEmail(email);

        userRepository.delete(user);
    }

    private void deleteCvFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }

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
}
