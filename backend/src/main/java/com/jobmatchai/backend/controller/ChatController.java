package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.service.AIChatService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:5173")
public class ChatController {

    @Autowired
    private AIChatService aiChatService;

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "").trim();
        String language = body.getOrDefault("language", "en").trim();

        if (message.isBlank()) {
            return ResponseEntity.badRequest().body("Message is required.");
        }

        String reply = aiChatService.chat(message, language);

        if (reply == null || reply.isBlank()) {
            return ResponseEntity.internalServerError().body("No reply from AI.");
        }

        return ResponseEntity.ok(Map.of("reply", reply));
    }
}
