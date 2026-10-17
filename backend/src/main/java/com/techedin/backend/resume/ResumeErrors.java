package com.techedin.backend.resume;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {ResumeController.class, ResumeEvaluationController.class, CareerChatController.class})
public class ResumeErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode());
        if (exception.getStatusCode().value() == 429) response.header("Retry-After", "3");
        return response.body(Map.of("message", exception.getReason()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestPartException.class})
    ResponseEntity<?> validation(Exception exception) {
        return ResponseEntity.badRequest().body(Map.of("message", "Selecione um arquivo válido ou revise o texto informado."));
    }
}

