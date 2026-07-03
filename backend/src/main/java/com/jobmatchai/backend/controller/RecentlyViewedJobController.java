package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.model.RecentlyViewedJob;
import com.jobmatchai.backend.service.RecentlyViewedJobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/recently-viewed")
public class RecentlyViewedJobController {

    @Autowired
    private RecentlyViewedJobService recentlyViewedJobService;

    public record TrackRequest(
            Long jobId, String jobType,
            String jobTitle, String companyName, String location
    ) {}

    @GetMapping("/test")
    public String test() {
        return "Recently Viewed API is working";
    }

    @PostMapping("/track")
    public Map<String, Object> track(@RequestBody TrackRequest request, Authentication authentication) {
        Map<String, Object> response = new HashMap<>();

        try {
            if (request.jobId() == null || request.jobType() == null) {
                response.put("success", false);
                return response;
            }

            recentlyViewedJobService.recordView(
                    authentication.getName(), request.jobId(), request.jobType(),
                    request.jobTitle(), request.companyName(), request.location());

            response.put("success", true);
            return response;
        } catch (Exception e) {
            response.put("success", false);
            return response;
        }
    }

    @GetMapping("/candidate/{email}")
    public List<RecentlyViewedJob> getRecentlyViewed(Authentication authentication) {
        return recentlyViewedJobService.getRecentlyViewed(authentication.getName());
    }
}
