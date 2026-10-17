package com.techedin.backend.resume;

import com.techedin.backend.account.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/account/career-chat")
public class CareerChatController {
    public record Message(@NotNull @Pattern(regexp="user|model") String role, @NotBlank @Size(max=4000) String text) {}
    public record Question(@NotBlank @Size(max=2000) String message, @NotNull @Size(max=8) List<@NotNull @Valid Message> history, java.util.UUID resumeRevision) {}
    public record Answer(String text, java.util.UUID resumeRevision) {}
    private final CareerChatService service;
    public CareerChatController(CareerChatService service) { this.service = service; }
    @PostMapping public Answer ask(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody Question question) {
        return service.ask(user.id(), question);
    }
}
