package com.omnibank.appmaprec.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Translates the recording controllers' exceptions into JSON error
 * envelopes so the JS client can render inline banners instead of the
 * default Spring "whitelabel" HTML page that breaks the SPA.
 */
@RestControllerAdvice(basePackageClasses = RecordingController.class)
public class RecordingExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return error(HttpStatus.CONFLICT, e.getMessage(), "recording.illegal_state");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), "recording.bad_request");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status,
                                                             String message,
                                                             String code) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "code", code
        ));
    }
}
