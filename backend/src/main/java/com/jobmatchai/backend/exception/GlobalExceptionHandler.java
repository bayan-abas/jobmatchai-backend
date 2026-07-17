package com.jobmatchai.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);

        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map((FieldError fieldError) -> fieldError.getDefaultMessage())
                .orElse("Invalid request");

        response.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Thrown at multipart-parsing time (before any controller method body runs) whenever a
    // request exceeds spring.servlet.multipart.max-file-size/max-request-size - without this
    // handler it falls through to Spring Boot's generic error page instead of this app's normal
    // {"success":false,"message":...} shape. Endpoint-specific limits (e.g. CVController's own
    // CV-size check) still produce their own more specific message when a request is under this
    // global ceiling; this is only the outermost safety net.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "The uploaded file is too large.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
