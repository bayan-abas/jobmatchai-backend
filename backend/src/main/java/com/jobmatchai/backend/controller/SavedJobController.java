package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.SavedJob;
import com.jobmatchai.backend.repository.SavedJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/saved-jobs")
public class SavedJobController {

    @Autowired
    private SavedJobRepository savedJobRepository;

    @GetMapping("/test")
    public String test() {
        return "Saved Jobs API is working";
    }

    @GetMapping("/candidate/{email}")
    public List<SavedJob> getSavedJobsByCandidate(Authentication authentication) {
        return savedJobRepository.findByCandidateEmailOrderBySavedAtDesc(authentication.getName());
    }

    @PostMapping("/save")
    @PreAuthorize("hasRole('CANDIDATE')")
    public Map<String, Object> saveJob(@RequestBody SavedJob savedJob, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();
        savedJob.setCandidateEmail(authentication.getName());

        try {
            if (savedJob.getJobId() == null || savedJob.getJobType() == null) {
                response.put("success", false);
                response.put("message", "jobId and jobType are required");
                return response;
            }

            boolean alreadySaved = savedJobRepository
                    .findByCandidateEmailAndJobIdAndJobType(savedJob.getCandidateEmail(), savedJob.getJobId(), savedJob.getJobType())
                    .isPresent();

            if (alreadySaved) {
                response.put("success", true);
                response.put("message", "Job already saved");
                return response;
            }

            savedJob.setSavedAt(LocalDate.now().toString());
            SavedJob saved = savedJobRepository.save(savedJob);

            response.put("success", true);
            response.put("message", "Job saved successfully");
            response.put("savedJob", saved);

            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @Transactional
    @DeleteMapping("/candidate/{email}/{jobType}/{jobId}")
    public Map<String, Object> unsaveJob(@PathVariable String jobType, @PathVariable Long jobId, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            savedJobRepository.deleteByCandidateEmailAndJobIdAndJobType(authentication.getName(), jobId, jobType);
            response.put("success", true);
            response.put("message", "Job removed from favorites");
            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}
