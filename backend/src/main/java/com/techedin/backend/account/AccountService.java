package com.techedin.backend.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountService implements UserDetailsService {
    public record Preferences(String targetRole, String location, String workModel, String seniority) {}
    public record AccountView(UUID id, String email, String displayName, Preferences preferences, boolean emailVerified) {
        public AccountView(UUID id,String email,String displayName,Preferences preferences) { this(id,email,displayName,preferences,false); }
    }
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.encoder = encoder;
    }
    public static String normalizeEmail(String email) { return email.strip().toLowerCase(Locale.ROOT); }

    @Override public UserDetails loadUserByUsername(String email) {
        return jdbc.query("SELECT user_id, email, password_hash, auth_version FROM app_user WHERE email = ?",
            (rs, row) -> new AccountPrincipal(rs.getObject("user_id", UUID.class),
                rs.getString("email"), rs.getString("password_hash"), rs.getLong("auth_version")), normalizeEmail(email))
            .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Credenciais inválidas"));
    }

    @Transactional
    public UUID register(String email, String name, String password) {
        if (password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72 || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Revise o nome e a senha informados.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO app_user(user_id, email, display_name, password_hash) VALUES (?, ?, ?, ?)",
                id, normalizeEmail(email), name.strip(), encoder.encode(password));
            jdbc.update("INSERT INTO app_user_preferences(user_id) VALUES (?)", id);
            return id;
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não foi possível criar uma conta com esses dados.");
        }
    }

    public AccountView view(UUID userId) {
        return jdbc.queryForObject("""
            SELECT u.user_id, u.email, u.display_name, u.email_verified_at, p.target_role, p.location, p.work_model, p.seniority
            FROM app_user u JOIN app_user_preferences p ON p.user_id = u.user_id WHERE u.user_id = ?
            """, (rs, row) -> new AccountView(rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), new Preferences(rs.getString("target_role"), rs.getString("location"),
                    rs.getString("work_model"), rs.getString("seniority")), rs.getObject("email_verified_at")!=null), userId);
    }

    @Transactional
    public AccountView update(UUID userId, String displayName, Preferences preferences) {
        jdbc.update("UPDATE app_user SET display_name = ? WHERE user_id = ?", displayName.strip(), userId);
        jdbc.update("""
            UPDATE app_user_preferences SET target_role = ?, location = ?, work_model = ?,
                seniority = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?
            """, preferences.targetRole().strip(), preferences.location().strip(),
                preferences.workModel(), preferences.seniority(), userId);
        return view(userId);
    }
    public boolean sessionValid(AccountPrincipal principal,boolean requireVerified) {
        return jdbc.query("SELECT auth_version,email_verified_at FROM app_user WHERE user_id=?",(rs,row)->
            rs.getLong("auth_version")==principal.authVersion() && (!requireVerified || rs.getObject("email_verified_at")!=null),principal.id()).stream().findFirst().orElse(false);
    }

}
