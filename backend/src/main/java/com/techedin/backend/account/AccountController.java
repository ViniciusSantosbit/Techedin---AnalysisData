package com.techedin.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AccountController {
    public record SessionView(boolean authenticated, AccountService.AccountView account) {}
    public record Registration(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=100) String displayName, @NotNull @Size(min=12, max=64) String password) {}
    public record Login(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=64) String password) {}
    public record ProfileUpdate(@NotBlank @Size(max=100) String displayName,
        @NotNull @Size(max=120) String targetRole, @NotNull @Size(max=120) String location,
        @NotNull @Pattern(regexp="any|remote|hybrid|onsite") String workModel,
        @NotNull @Pattern(regexp="any|internship|junior|mid|senior|specialist") String seniority) {}
    private final AccountService service;
    private final AccountLifecycle lifecycle;
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final AuthAttemptLimiter limiter;

    public AccountController(AccountService service, AuthenticationManager manager,
            SecurityContextRepository contexts, SessionAuthenticationStrategy sessions, AuthAttemptLimiter limiter, AccountLifecycle lifecycle) {
        this.lifecycle=lifecycle;
        this.service = service; this.manager = manager; this.contexts = contexts; this.sessions = sessions; this.limiter = limiter;
    }
    @GetMapping("/auth/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/auth/session") public SessionView session(@AuthenticationPrincipal AccountPrincipal user) {
        return user == null ? new SessionView(false, null) : new SessionView(true, service.view(user.id()));
    }
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public Object register(@Valid @RequestBody Registration body,
            HttpServletRequest request, HttpServletResponse response) {
        limiter.check(request.getRemoteAddr(), true);
        if (lifecycle.requiresVerification()) {
            lifecycle.register(body.email(),body.displayName(),body.password());
            return Map.of("verificationRequired",true,"message","Enviamos um link para confirmar seu e-mail. Depois, entre na conta.");
        }
        service.register(body.email(), body.displayName(), body.password());
        return authenticate(body.email(), body.password(), request, response);
    }
    @PostMapping("/auth/login")
    public AccountService.AccountView login(@Valid @RequestBody Login body,
            HttpServletRequest request, HttpServletResponse response) {
        limiter.check(request.getRemoteAddr(), false);
        return authenticate(body.email(), body.password(), request, response);
    }
    private AccountService.AccountView authenticate(String email, String password,
            HttpServletRequest request, HttpServletResponse response) {
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        if (lifecycle.requiresVerification() && !service.view(((AccountPrincipal)authentication.getPrincipal()).id()).emailVerified())
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,"Confirme seu e-mail antes de entrar. Use Reenviar confirmação se precisar de outro link.");
        sessions.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return service.view(((AccountPrincipal) authentication.getPrincipal()).id());
    }
    @GetMapping("/account") public AccountService.AccountView account(@AuthenticationPrincipal AccountPrincipal user) {
        return service.view(user.id());
    }
    @PutMapping("/account/preferences")
    public AccountService.AccountView preferences(@AuthenticationPrincipal AccountPrincipal user,
            @Valid @RequestBody ProfileUpdate body) {
        return service.update(user.id(), body.displayName(),
            new AccountService.Preferences(body.targetRole(), body.location(), body.workModel(), body.seniority()));
    }
}
