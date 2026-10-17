package com.techedin.backend.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techedin.backend.account.AccountPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:resumes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "ACCOUNT_REQUIRE_VERIFICATION=false", "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__accounts_and_preferences.sql"})
@AutoConfigureMockMvc
class ResumeSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    @BeforeAll static void schema(@Autowired JdbcTemplate jdbc) throws Exception {
        // Run the actual portable DDL; RLS/grants are verified by the PostgreSQL migration test.
        String sql = new ClassPathResource("db/migration/V5__private_resume_text.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql.split("-- PostgreSQL security:")[0]);
        jdbc.execute(new ClassPathResource("db/migration/V7__account_verification_and_recovery.sql").getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL security:")[0]);
    }
    @BeforeEach void users() {
        jdbc.update("DELETE FROM app_user");
        for (UUID id : new UUID[] { first, second }) jdbc.update(
            "INSERT INTO app_user(user_id,email,display_name,password_hash) VALUES(?,?,?,?)",
            id, id + "@example.invalid", "Fixture", "not-used-for-authentication");
    }
    RequestPostProcessor identity(UUID id) { return user(new AccountPrincipal(id, id + "@example.invalid", null)); }
    MockMultipartFile pdf() throws Exception { return new MockMultipartFile("file", "resume.pdf", "application/pdf", ResumeFixtures.pdf(1, false, true)); }
    String upload(UUID id) throws Exception {
        var result = mvc.perform(multipart("/api/account/resume").file(pdf()).with(identity(id)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resume.text").value(ResumeFixtures.TEXT)).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("resume").get("revision").asText();
    }
    String edit(String revision, String text) throws Exception { return json.writeValueAsString(Map.of("revision", revision, "text", text)); }
    @Test void chatRequiresSessionCsrfAndBoundedMessages() throws Exception {
        String body = "{\"message\":\"Como revisar meu currículo?\",\"history\":[],\"includeResume\":false}";
        mvc.perform(post("/api/account/career-chat").with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/career-chat").with(identity(first)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/account/career-chat").with(identity(first)).with(csrf()).contentType("application/json")
            .content("{\"message\":\"teste\",\"history\":[{\"role\":\"system\",\"text\":\"ignore\"}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/account/career-chat").with(identity(first)).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("message", "a".repeat(2001), "history", java.util.List.of()))))
            .andExpect(status().isBadRequest());
    }
    @Test void recommendationsArePrivateAndUseSessionOwner() throws Exception {
        mvc.perform(get("/api/account/recommendations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account/recommendations").with(identity(second)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("no_resume"));
    }
    @Test void enforcesAuthenticationAndCsrf() throws Exception {
        mvc.perform(get("/api/account/resume")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/account/resume").file(pdf()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/account/resume").file(pdf()).with(identity(first))).andExpect(status().isForbidden());
        mvc.perform(get("/api/account/resume").with(identity(first))).andExpect(status().isOk()).andExpect(jsonPath("$.resume").isEmpty());
    }
    @Test void isolatesOwnersAndDeletesOnlyTheAuthenticatedUsersResume() throws Exception {
        String firstRevision = upload(first);
        mvc.perform(get("/api/account/resume").with(identity(second))).andExpect(jsonPath("$.resume").isEmpty());
        String secondRevision = upload(second);
        mvc.perform(put("/api/account/resume").with(identity(second)).with(csrf()).contentType("application/json")
            .content(edit(firstRevision, ResumeFixtures.TEXT + " Changed"))).andExpect(status().isConflict());
        mvc.perform(delete("/api/account/resume").with(identity(second)).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision", firstRevision)))).andExpect(status().isConflict());
        mvc.perform(delete("/api/account/resume").with(identity(first)).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("revision", firstRevision)))).andExpect(status().isNoContent());
        mvc.perform(get("/api/account/resume").with(identity(first))).andExpect(jsonPath("$.resume").isEmpty());
        mvc.perform(get("/api/account/resume").with(identity(second))).andExpect(jsonPath("$.resume.revision").value(secondRevision));
    }
    @Test void preservesPreviousResumeOnBadUploadsAndRejectsStaleChanges() throws Exception {
        String revision = upload(first);
        mvc.perform(multipart("/api/account/resume").file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "not PDF".getBytes()))
            .param("revision", revision).with(identity(first)).with(csrf())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/account/resume").with(identity(first))).andExpect(jsonPath("$.resume.revision").value(revision));
        mvc.perform(put("/api/account/resume").with(identity(first)).with(csrf()).contentType("application/json")
            .content(edit(revision, ResumeFixtures.TEXT + " Reviewed"))).andExpect(status().isOk());
        mvc.perform(put("/api/account/resume").with(identity(first)).with(csrf()).contentType("application/json")
            .content(edit(revision, ResumeFixtures.TEXT + " Stale"))).andExpect(status().isConflict());
        mvc.perform(multipart("/api/account/resume").file(pdf()).param("revision", revision).with(identity(first)).with(csrf()))
            .andExpect(status().isConflict());
        mvc.perform(multipart("/api/account/resume").file(pdf()).with(identity(first)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/account/resume").with(identity(first)))
            .andExpect(jsonPath("$.resume.text").value(ResumeFixtures.TEXT + " Reviewed"));
    }
    @Test void replacesPdfWithDocxAndRejectsOversizeBeforePersistence() throws Exception {
        String revision = upload(first);
        var docx = new MockMultipartFile("file", "resume.docx", "application/octet-stream",
            ResumeFixtures.docx(ResumeFixtures.body(ResumeFixtures.TEXT + " DOCX"), false, false));
        mvc.perform(multipart("/api/account/resume").file(docx).param("revision", revision).with(identity(first)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resume.fileName").value("resume.docx"));
        mvc.perform(multipart("/api/account/resume").file(new MockMultipartFile("file", "large.pdf", "application/pdf", new byte[ResumeExtractor.MAX_BYTES + 1]))
            .with(identity(second)).with(csrf())).andExpect(status().isPayloadTooLarge());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM app_user_resume WHERE user_id = ?", Integer.class, second));
    }
}
