package com.tssc.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> validation(MethodArgumentNotValidException exception) {
        Map<String,String> errors = new LinkedHashMap<>();
        for (FieldError e : exception.getBindingResult().getFieldErrors()) errors.putIfAbsent(e.getField(), e.getDefaultMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "validation_failed", "details", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformed(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", "invalid_json", "message", "Request body is missing or malformed."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> unexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "internal_error", "message", "The request could not be completed."));
    }
}
