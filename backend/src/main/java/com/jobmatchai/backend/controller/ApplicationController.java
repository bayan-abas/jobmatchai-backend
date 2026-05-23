package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Application;
import com.jobmatchai.backend.repository.ApplicationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/applications")
@CrossOrigin(origins = "http://localhost:5173")
public class ApplicationController {

    @Autowired
    private ApplicationRepository applicationRepository;

    @GetMapping("/test")
    public String test() {
        return "Applications API is working";
    }

    @GetMapping("/all")
    public List<Application> getAllApplications() {
        return applicationRepository.findAll();
    }

    @GetMapping("/candidate/{email}")
    public List<Application> getApplicationsByCandidate(@PathVariable String email) {
        return applicationRepository.findByCandidateEmail(email);
    }

    @PostMapping("/apply")
    public Map<String, Object> applyToJob(@RequestBody Application application) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (application.getCandidateEmail() == null || application.getJobId() == null) {
                response.put("success", false);
                response.put("message", "candidateEmail and jobId are required");
                return response;
            }

            boolean alreadyApplied = applicationRepository
                    .findByCandidateEmailAndJobId(application.getCandidateEmail(), application.getJobId())
                    .isPresent();

            if (alreadyApplied) {
                response.put("success", false);
                response.put("message", "You already applied to this job");
                return response;
            }

            application.setStatus("Under Review");
            application.setAppliedDate(LocalDate.now().toString());

            Application savedApplication = applicationRepository.save(application);

            response.put("success", true);
            response.put("message", "Application submitted successfully");
            response.put("application", savedApplication);

            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}