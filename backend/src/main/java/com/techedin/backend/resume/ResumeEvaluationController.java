package com.techedin.backend.resume;

import com.techedin.backend.account.AccountPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/account/resume/evaluation")
public class ResumeEvaluationController {
    private final ResumeEvaluationService service;
    public ResumeEvaluationController(ResumeEvaluationService service) { this.service = service; }
    @GetMapping public Map<String, Boolean> status() { return Map.of("configured", service.configured()); }
    @PostMapping public ResumeEvaluationService.Evaluation evaluate(@AuthenticationPrincipal AccountPrincipal user,
            @Valid @RequestBody ResumeController.Revision body) { return service.evaluate(user.id(), body.revision()); }
}
