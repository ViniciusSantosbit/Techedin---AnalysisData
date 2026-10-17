package com.techedin.backend.recommendation;
import com.techedin.backend.account.AccountPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/account/recommendations")
public class RecommendationController {
    private final RecommendationService service;
    public RecommendationController(RecommendationService service) { this.service = service; }
    @GetMapping public RecommendationService.Result get(@AuthenticationPrincipal AccountPrincipal user) { return service.get(user.id()); }
}
