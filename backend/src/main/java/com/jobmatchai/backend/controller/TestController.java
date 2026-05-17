package com.jobmatchai.backend.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
@CrossOrigin(origins = "http://localhost:5173")
public class TestController {

    @GetMapping
    public String test() {
        return "Backend Connected Successfully 🚀";
    }
}