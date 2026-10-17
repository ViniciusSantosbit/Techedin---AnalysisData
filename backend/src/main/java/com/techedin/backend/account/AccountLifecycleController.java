package com.techedin.backend.account;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountLifecycleController {
    public record Email(@NotBlank @jakarta.validation.constraints.Email @Size(max=254) String email) {}
    public record Token(@NotBlank @Size(max=100) String token) {}
    public record Reset(@NotBlank @Size(max=100) String token,@NotNull @Size(min=12,max=64) String password) {}
    public record Delete(@NotBlank @Size(max=64) String password,@NotBlank @Size(max=20) String confirmation) {}
    private final AccountLifecycle lifecycle;
    private final AccountMail mail;
    private final AuthAttemptLimiter limiter;
    public AccountLifecycleController(AccountLifecycle lifecycle,AccountMail mail,AuthAttemptLimiter limiter) {
        this.lifecycle=lifecycle; this.mail=mail; this.limiter=limiter;
    }
    @GetMapping("/auth/options") public Object options() {
        return Map.of("emailConfirmationRequired",lifecycle.requiresVerification(),"localMailPreview",mail.local(),"emailDeliveryEnabled",mail.enabled());
    }
    @PostMapping("/auth/verification/request") public Object verification(@Valid @RequestBody Email body,HttpServletRequest request) {
        return send(body,request,"verification");
    }
    @PostMapping("/auth/password/request") public Object recovery(@Valid @RequestBody Email body,HttpServletRequest request) {
        return send(body,request,"reset");
    }
    private Object send(Email body,HttpServletRequest request,String purpose) {
        if (!mail.enabled()) throw new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "O envio de e-mails está desativado neste projeto.");
        limiter.check(request.getRemoteAddr(),true);
        try { lifecycle.request(body.email(),purpose); }
        catch (ResponseStatusException error) { if (error.getStatusCode().value()!=503) throw error; }
        return Map.of("message","Se houver uma conta elegível, enviaremos um link. Confira o e-mail e a pasta de spam.");
    }
    @PostMapping("/auth/verification/confirm") public Object confirm(@Valid @RequestBody Token body,HttpServletRequest request) {
        limiter.check(request.getRemoteAddr(),false);
        lifecycle.consume(body.token(),"verification",null);
        return Map.of("message","E-mail confirmado. Você já pode entrar.");
    }
    @PostMapping("/auth/password/reset") public Object reset(@Valid @RequestBody Reset body,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr(),false);
        lifecycle.consume(body.token(),"reset",body.password());
        new SecurityContextLogoutHandler().logout(request,response,null);
        return Map.of("message","Senha alterada. Entre novamente com a nova senha.");
    }
    @DeleteMapping("/account") public void delete(@AuthenticationPrincipal AccountPrincipal user,
        @Valid @RequestBody Delete body,HttpServletRequest request,HttpServletResponse response) {
        limiter.check(request.getRemoteAddr(),false);
        lifecycle.delete(user.id(),body.password(),body.confirmation());
        new SecurityContextLogoutHandler().logout(request,response,null);
        response.setStatus(204);
    }
}
