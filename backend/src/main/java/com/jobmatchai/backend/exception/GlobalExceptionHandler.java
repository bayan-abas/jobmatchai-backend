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

    // תופס שגיאות ולידציה של @Valid ומחזיר רק את הודעת השגיאה הראשונה בפורמט JSON אחיד במקום כל השגיאות של Spring
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

    // תופס קובץ גדול מדי שהועלה ומחזיר הודעה ברורה למשתמש במקום שגיאת שרת גנרית
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", false);
        response.put("message", "The uploaded file is too large.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
