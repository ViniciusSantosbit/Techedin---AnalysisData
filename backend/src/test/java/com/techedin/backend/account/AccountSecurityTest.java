package com.techedin.backend.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techedin.backend.service.TechnologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:accounts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__accounts_and_preferences.sql"})
@AutoConfigureMockMvc
class AccountSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @LocalServerPort int port;
    @MockitoBean TechnologyService technologies;
    private static final String PASSWORD = "Uma senha longa 123!";

    @BeforeEach void clear() { jdbc.update("DELETE FROM app_user"); }
    String csrf(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
    MockHttpSession register(String email) throws Exception {
        var session = new MockHttpSession();
        String originalId = session.getId();
        var result = mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content(json.writeValueAsString(Map.of(
                "email", email, "displayName", "Pessoa Teste", "password", PASSWORD))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.preferences.workModel").value("any"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        assertNotEquals(originalId, session.getId());
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    String preferences(String role) throws Exception {
        return json.writeValueAsString(Map.of("displayName", "Pessoa", "targetRole", role,
            "location", "São Paulo", "workModel", "remote", "seniority", "junior"));
    }
    @Test void registerLoginLogoutAndCsrfProtectSession() throws Exception {
        mvc.perform(get("/api/auth/session")).andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.account").isEmpty());
        mvc.perform(get("/api/account")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/technologies")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        var session = register("pessoa@example.com");
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.account.email").value("pessoa@example.com"))
            .andExpect(jsonPath("$.account.passwordHash").doesNotExist());
        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user", String.class);
        assertNotEquals(PASSWORD, stored);
        assertTrue(stored.startsWith("$2"));
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertNull(((AccountPrincipal) context.getAuthentication().getPrincipal()).getPassword());
        mvc.perform(put("/api/account/preferences").session(session).contentType("application/json").content(preferences("Java")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/account/preferences").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content(preferences("Java")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preferences.targetRole").value("Java"));
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", csrf(session)))
            .andExpect(status().isNoContent());
        assertTrue(session.isInvalid());
        mvc.perform(get("/api/auth/session")).andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false)).andExpect(jsonPath("$.account").isEmpty());
        mvc.perform(get("/api/account")).andExpect(status().isUnauthorized());
        var fresh = new MockHttpSession();
        String oldToken = csrf(fresh);
        mvc.perform(post("/api/auth/login").session(fresh).header("X-CSRF-TOKEN", oldToken)
            .contentType("application/json").content(json.writeValueAsString(Map.of("email", "PESSOA@example.com", "password", PASSWORD))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.preferences.targetRole").value("Java"));
        mvc.perform(put("/api/account/preferences").session(fresh).header("X-CSRF-TOKEN", oldToken)
            .contentType("application/json").content(preferences("Python"))).andExpect(status().isForbidden());
    }
    @Test void accountsCannotReadOrOverwriteEachOthersPreferences() throws Exception {
        var first = register("first@example.com");
        var second = register("second@example.com");
        mvc.perform(put("/api/account/preferences").session(first).header("X-CSRF-TOKEN", csrf(first))
            .contentType("application/json").content(preferences("Python"))).andExpect(status().isOk());
        mvc.perform(get("/api/account").session(second)).andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("second@example.com"))
            .andExpect(jsonPath("$.preferences.targetRole").value(""));
        mvc.perform(get("/api/account").session(first)).andExpect(status().isOk())
            .andExpect(jsonPath("$.preferences.targetRole").value("Python"));
    }
    @Test void rejectsInvalidCredentialsDuplicatesAndInvalidProfile() throws Exception {
        var first = register("unique@example.com");
        var session = new MockHttpSession();
        mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content(json.writeValueAsString(Map.of("email", "UNIQUE@example.com",
                "displayName", "Pessoa", "password", PASSWORD)))).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", csrf(session))
            .contentType("application/json").content(json.writeValueAsString(Map.of("email", "unique@example.com", "password", "incorrect"))))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/account/preferences").session(first).header("X-CSRF-TOKEN", csrf(first))
            .contentType("application/json").content(preferences("Java").replace("remote", "unknown")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/account").session(first)).andExpect(jsonPath("$.preferences.targetRole").value(""));
    }
    @Test void permitsCredentialedCorsOnlyForConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/account/preferences").header("Origin", "http://127.0.0.1:5500")
            .header("Access-Control-Request-Method", "PUT").header("Access-Control-Request-Headers", "X-CSRF-TOKEN,Content-Type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5500"));
        mvc.perform(options("/api/account/preferences").header("Origin", "http://localhost:8081")
            .header("Access-Control-Request-Method", "PUT").header("Access-Control-Request-Headers", "X-CSRF-TOKEN,Content-Type"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
        mvc.perform(options("/api/account/preferences").header("Origin", "https://untrusted.example")
            .header("Access-Control-Request-Method", "PUT")).andExpect(status().isForbidden());
    }
    @Test void cookieSessionWorksOverRealHttpAndIsInvalidatedAtLogout() throws Exception {
        var cookies = new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL);
        var client = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
        String base = "http://localhost:" + port;
        var csrfResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/auth/csrf")).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, csrfResponse.statusCode());
        String cookie = csrfResponse.headers().firstValue("set-cookie").orElseThrow().toLowerCase();
        assertTrue(cookie.contains("httponly"));
        assertTrue(cookie.contains("samesite=lax"));
        String token = json.readTree(csrfResponse.body()).get("token").asText();
        var registration = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/auth/register"))
            .header("Content-Type", "application/json").header("X-CSRF-TOKEN", token)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "email", "http@example.com", "displayName", "HTTP", "password", PASSWORD)))).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(201, registration.statusCode(), registration.body());
        var accountRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/account")).GET().build();
        assertEquals(200, client.send(accountRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, java.net.http.HttpClient.newHttpClient().send(accountRequest,
            java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        var freshCsrf = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/auth/csrf")).GET().build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        var logout = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/auth/logout"))
            .header("X-CSRF-TOKEN", json.readTree(freshCsrf.body()).get("token").asText())
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(204, logout.statusCode());
        assertEquals(401, client.send(accountRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void throttlesRepeatedAttemptsWithoutTrustingUserProvidedIdentity() {
        var limiter = new AuthAttemptLimiter();
        for (int i = 0; i < 30; i++) limiter.check("peer", false);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> limiter.check("peer", false));
        assertDoesNotThrow(() -> limiter.check("other-peer", false));
        for (int i = 0; i < 10; i++) limiter.check("peer", true);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> limiter.check("peer", true));
    }
}
