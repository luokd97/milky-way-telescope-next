package com.milkywaytelescope.next.api;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> illegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorView(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorView> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new ErrorView(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                "URL and access token are required"
        ));
    }

    public record ErrorView(Instant timestamp, int status, String message) {
    }
}
