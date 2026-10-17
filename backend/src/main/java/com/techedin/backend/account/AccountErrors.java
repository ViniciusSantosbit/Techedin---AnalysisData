package com.techedin.backend.account;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = {AccountController.class, AccountLifecycleController.class})
public class AccountErrors {
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<?> authentication(AuthenticationException exception) {
        return ResponseEntity.status(401).body(Map.of("message", "E-mail ou senha inválidos."));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().hasFieldErrors("password")
            ? "A senha deve ter entre 12 e 64 caracteres." : "Revise os campos informados.";
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException exception) {
        var response = ResponseEntity.status(exception.getStatusCode());
        if (exception.getStatusCode().value() == 429) response.header("Retry-After", "600");
        return response.body(Map.of("message", exception.getReason()));
    }
}
