package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.Job;
import com.jobmatchai.backend.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "http://localhost:5173")
public class JobController {

    @Autowired
    private JobRepository jobRepository;

    @GetMapping("/test")
    public String test() {
        return "Jobs API is working";
    }

    @GetMapping("/all")
    public List<Job> getAllJobs() {
        return jobRepository.findAll();
    }

    @PostMapping("/add")
    public Map<String, Object> addJob(@RequestBody Job job) {
        Map<String, Object> response = new HashMap<>();

        try {
            Job savedJob = jobRepository.save(job);

            response.put("success", true);
            response.put("message", "Job added successfully");
            response.put("job", savedJob);

            return response;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> getJobById(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        return jobRepository.findById(id)
                .map(job -> {
                    response.put("success", true);
                    response.put("job", job);
                    return response;
                })
                .orElseGet(() -> {
                    response.put("success", false);
                    response.put("message", "Job not found");
                    return response;
                });
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateJob(@PathVariable Long id, @RequestBody Job updatedJob) {
        Map<String, Object> response = new HashMap<>();

        try {
            return jobRepository.findById(id)
                    .map(job -> {
                        job.setTitle(updatedJob.getTitle());
                        job.setCompanyName(updatedJob.getCompanyName());
                        job.setLocation(updatedJob.getLocation());
                        job.setType(updatedJob.getType());
                        job.setSalary(updatedJob.getSalary());
                        job.setDescription(updatedJob.getDescription());
                        job.setRequirements(updatedJob.getRequirements());
                        job.setSkills(updatedJob.getSkills());

                        Job savedJob = jobRepository.save(job);

                        response.put("success", true);
                        response.put("message", "Job updated successfully");
                        response.put("job", savedJob);

                        return response;
                    })
                    .orElseGet(() -> {
                        response.put("success", false);
                        response.put("message", "Job not found");
                        return response;
                    });

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteJob(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (!jobRepository.existsById(id)) {
                response.put("success", false);
                response.put("message", "Job not found");
                return response;
            }

            jobRepository.deleteById(id);

            response.put("success", true);
            response.put("message", "Job deleted successfully");

            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return response;
        }
    }
}