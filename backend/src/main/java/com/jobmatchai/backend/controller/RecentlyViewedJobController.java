package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.RecentlyViewedJob;
import com.jobmatchai.backend.service.RecentlyViewedJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recently-viewed")
@CrossOrigin(origins = "http://localhost:5173")
public class RecentlyViewedJobController {

    @Autowired
    private RecentlyViewedJobService recentlyViewedJobService;

    public record TrackRequest(
            String candidateEmail, Long jobId, String jobType,
            String jobTitle, String companyName, String location
    ) {}

    @GetMapping("/test")
    public String test() {
        return "Recently Viewed API is working";
    }

    @PostMapping("/track")
    public Map<String, Object> track(@RequestBody TrackRequest request) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (request.candidateEmail() == null || request.jobId() == null || request.jobType() == null) {
                response.put("success", false);
                return response;
            }

            recentlyViewedJobService.recordView(
                    request.candidateEmail(), request.jobId(), request.jobType(),
                    request.jobTitle(), request.companyName(), request.location());

            response.put("success", true);
            return response;
        } catch (Exception e) {
            response.put("success", false);
            return response;
        }
    }

    @GetMapping("/candidate/{email}")
    public List<RecentlyViewedJob> getRecentlyViewed(@PathVariable String email) {
        return recentlyViewedJobService.getRecentlyViewed(email);
    }
}
