package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.SavedJob;
import com.jobmatchai.backend.repository.SavedJobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/saved-jobs")
@CrossOrigin(origins = "http://localhost:5173")
public class SavedJobController {

    @Autowired
    private SavedJobRepository savedJobRepository;

    @GetMapping("/test")
    public String test() {
        return "Saved Jobs API is working";
    }

    @GetMapping("/candidate/{email}")
    public List<SavedJob> getSavedJobsByCandidate(@PathVariable String email) {
        return savedJobRepository.findByCandidateEmailOrderBySavedAtDesc(email);
    }

    @PostMapping("/save")
    public Map<String, Object> saveJob(@RequestBody SavedJob savedJob) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (savedJob.getCandidateEmail() == null || savedJob.getJobId() == null || savedJob.getJobType() == null) {
                response.put("success", false);
                response.put("message", "candidateEmail, jobId and jobType are required");
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
    public Map<String, Object> unsaveJob(@PathVariable String email, @PathVariable String jobType, @PathVariable Long jobId) {
        Map<String, Object> response = new HashMap<>();

        try {
            savedJobRepository.deleteByCandidateEmailAndJobIdAndJobType(email, jobId, jobType);
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
