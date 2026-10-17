package com.techedin.backend.account;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AccountLifecycle {
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final PasswordEncoder encoder;
    private final AccountMail mail;
    private final boolean required;
    public boolean requiresVerification() { return required; }
    public AccountLifecycle(JdbcTemplate jdbc,AccountService accounts,PasswordEncoder encoder,AccountMail mail,
        @Value("${ACCOUNT_REQUIRE_VERIFICATION:false}") boolean required) {
        this.jdbc=jdbc; this.accounts=accounts; this.encoder=encoder; this.mail=mail; this.required=required;
    }
    @Transactional public void register(String email,String name,String password) {
        UUID id=accounts.register(email,name,password);
        issue(id,AccountService.normalizeEmail(email),"verification");
    }
    @Transactional public void request(String email,String purpose) {
        var users=jdbc.queryForList("SELECT user_id,email,email_verified_at FROM app_user WHERE email=? FOR UPDATE",AccountService.normalizeEmail(email));
        if (users.isEmpty()) return;
        var user=users.getFirst();
        if (purpose.equals("verification") && user.get("email_verified_at")!=null) return;
        UUID id=(UUID)user.get("user_id");
        Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM app_account_token WHERE user_id=? AND purpose=? AND created_at>?",Integer.class,
            id,purpose,Timestamp.from(Instant.now().minusSeconds(60)));
        if (recent!=null && recent>0) return;
        issue(id,(String)user.get("email"),purpose);
    }
    private void issue(UUID id,String email,String purpose) {
        byte[] random=new byte[32]; new SecureRandom().nextBytes(random);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.update("DELETE FROM app_account_token WHERE user_id=? AND purpose=?",id,purpose);
        jdbc.update("INSERT INTO app_account_token(token_hash,user_id,purpose,expires_at) VALUES(?,?,?,?)",
            hash(token),id,purpose,Timestamp.from(Instant.now().plusSeconds(purpose.equals("verification")?86400:1800)));
        mail.send(email,purpose,token);
    }
    @Transactional public void consume(String token,String purpose,String password) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        var candidate=jdbc.queryForList("SELECT user_id FROM app_account_token WHERE token_hash=? AND purpose=?",hash(token),purpose);
        if (candidate.isEmpty()) throw invalid();
        // All lifecycle mutations lock the user before its tokens, avoiding lock-order inversion.
        var owner=jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE",candidate.getFirst().get("user_id"));
        if (owner.isEmpty()) throw invalid();
        var rows=jdbc.queryForList("SELECT user_id FROM app_account_token WHERE token_hash=? AND purpose=? AND expires_at>CURRENT_TIMESTAMP FOR UPDATE",hash(token),purpose);
        if (rows.isEmpty()) throw invalid();
        UUID id=(UUID)rows.getFirst().get("user_id");
        if (purpose.equals("verification")) {
            jdbc.update("UPDATE app_user SET email_verified_at=CURRENT_TIMESTAMP WHERE user_id=?",id);
            jdbc.update("DELETE FROM app_account_token WHERE user_id=? AND purpose='verification'",id);
        } else {
            if (password==null || password.isBlank() || password.length()<12 || password.length()>64 || password.getBytes(StandardCharsets.UTF_8).length>72)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use uma senha de 12 a 64 caracteres e até 72 bytes.");
            jdbc.update("UPDATE app_user SET password_hash=?,auth_version=auth_version+1 WHERE user_id=?",encoder.encode(password),id);
            jdbc.update("DELETE FROM app_account_token WHERE user_id=?",id);
        }
    }
    @Transactional public void delete(UUID id,String password,String confirmation) {
        String stored=jdbc.queryForObject("SELECT password_hash FROM app_user WHERE user_id=? FOR UPDATE",String.class,id);
        if (!"EXCLUIR".equals(confirmation) || password==null || password.getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(password,stored))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Confira a senha atual e digite EXCLUIR para confirmar.");
        jdbc.update("DELETE FROM app_user WHERE user_id=?",id);
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Link inválido ou expirado. Solicite um novo e-mail."); }
    private static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
