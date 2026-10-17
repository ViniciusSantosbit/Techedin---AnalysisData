package com.techedin.backend.notification;
import com.techedin.backend.account.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestController
@RequestMapping("/api/account/notifications")
public class NotificationController {
    public record Subscription(@NotBlank @Size(max=4096) String endpoint) {}
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping public Map<String,Object> config() { return service.config(); }
    @PostMapping public void subscribe(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Subscription body) { service.subscribe(user.id(),body.endpoint()); }
    @PostMapping("/status") public Map<String,Boolean> status(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Subscription body) { return Map.of("subscribed",service.subscribed(user.id(),body.endpoint())); }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT) public void unsubscribe(@AuthenticationPrincipal AccountPrincipal user,@Valid @RequestBody Subscription body) { service.unsubscribe(user.id(),body.endpoint()); }
}
