package com.jobmatchai.backend.controller;

import com.jobmatchai.backend.service.AIChatContextService;
import com.jobmatchai.backend.service.AIChatService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:5173")
public class ChatController {

    @Autowired
    private AIChatService aiChatService;

    @Autowired
    private AIChatContextService aiChatContextService;

    private static final int MAX_HISTORY = 20;

    public record ChatMessage(String role, String content) {}

    public record ChatRequest(String email, String message, List<ChatMessage> history, String language) {}

    @PostMapping
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        String language = request.language() == null || request.language().isBlank()
                ? "en"
                : request.language().trim();
        String email = request.email() == null ? "" : request.email().trim();

        if (message.isBlank()) {
            return ResponseEntity.badRequest().body("Message is required.");
        }

        List<ChatMessage> rawHistory = request.history() == null ? List.of() : request.history();
        List<ChatMessage> trimmedHistory = rawHistory.size() > MAX_HISTORY
                ? rawHistory.subList(rawHistory.size() - MAX_HISTORY, rawHistory.size())
                : rawHistory;

        List<Map<String, String>> historyForService = trimmedHistory.stream()
                .map(turn -> Map.of(
                        "role", "ai".equals(turn.role()) || "assistant".equals(turn.role()) ? "assistant" : "user",
                        "content", turn.content() == null ? "" : turn.content()
                ))
                .toList();

        AIChatContextService.ChatContext context = aiChatContextService.buildContext(email, language);

        String reply = aiChatService.chat(message, language, context.contextBlock(), context.mode(), historyForService);

        if (reply == null || reply.isBlank()) {
            return ResponseEntity.internalServerError().body("No reply from AI.");
        }

        return ResponseEntity.ok(Map.of("reply", reply));
    }
}
