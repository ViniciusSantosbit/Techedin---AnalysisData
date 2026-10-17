package com.techedin.backend.notification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestControllerAdvice(assignableTypes=NotificationController.class)
public class NotificationErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> status(ResponseStatusException error) { return ResponseEntity.status(error.getStatusCode()).body(Map.of("message",error.getReason())); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class}) ResponseEntity<?> invalid(Exception error) { return ResponseEntity.badRequest().body(Map.of("message","Não foi possível registrar este dispositivo. Revise a permissão de notificações.")); }
}
