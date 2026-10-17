package com.techedin.backend.resume;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import java.util.Map;

@RestControllerAdvice
public class UploadLimitErrors {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> limit(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(Map.of("message", "O currículo deve ter no máximo 5 MB."));
    }
}
