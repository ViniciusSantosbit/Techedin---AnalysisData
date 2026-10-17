package com.techedin.backend.account;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=none","spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__accounts_and_preferences.sql","ACCOUNT_REQUIRE_VERIFICATION=true"})
@AutoConfigureMockMvc
class AccountLifecycleTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper json;
    @Autowired AccountLifecycle lifecycle; @Autowired AccountService accounts;
    @MockitoBean AccountMail mail;
    String verification, resetToken;
    final String email="fixture@example.invalid", password="Synthetic password 123!";
    @BeforeAll static void schema(@Autowired JdbcTemplate jdbc) throws Exception {
        for (String migration:List.of("V5__private_resume_text.sql","V7__account_verification_and_recovery.sql"))
            jdbc.execute(new ClassPathResource("db/migration/"+migration).getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL security:")[0]);
    }
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM app_user"); reset(mail); when(mail.enabled()).thenReturn(true);
        doAnswer(call->{ if (call.getArgument(1).equals("verification")) verification=call.getArgument(2); else resetToken=call.getArgument(2); return null; })
            .when(mail).send(anyString(),anyString(),anyString());
    }
    String csrf(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/auth/csrf").session(session)).andReturn().getResponse().getContentAsString()).get("token").asText();
    }
    org.springframework.test.web.servlet.ResultActions postJson(String path,MockHttpSession session,Object body) throws Exception {
        return mvc.perform(post(path).session(session).header("X-CSRF-TOKEN",csrf(session)).contentType("application/json").content(json.writeValueAsString(body)));
    }
    MockHttpSession login() throws Exception {
        var session=new MockHttpSession();
        var result=postJson("/api/auth/login",session,Map.of("email",email,"password",password)).andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    @Test void registrationRequiresConfirmationAndTokenIsHashedSingleUseAndPurposeBound() throws Exception {
        var session=new MockHttpSession();
        postJson("/api/auth/register",session,Map.of("email",email,"displayName","Fixture","password",password))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.verificationRequired").value(true));
        mvc.perform(get("/api/account").session(session)).andExpect(status().isUnauthorized());
        postJson("/api/auth/login",session,Map.of("email",email,"password",password)).andExpect(status().isForbidden());
        assertNotEquals(verification,jdbc.queryForObject("SELECT token_hash FROM app_account_token",String.class));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->lifecycle.consume(verification,"reset",password));
        postJson("/api/auth/verification/confirm",session,Map.of("token",verification)).andExpect(status().isOk());
        postJson("/api/auth/verification/confirm",session,Map.of("token",verification)).andExpect(status().isBadRequest());
        login();
    }
    @Test void passwordResetRevokesExistingSessionsAndRejectsExpiredToken() throws Exception {
        lifecycle.register(email,"Fixture",password); lifecycle.consume(verification,"verification",null);
        var session=login(); lifecycle.request(email,"reset");
        String token=resetToken;
        jdbc.update("UPDATE app_account_token SET expires_at=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->lifecycle.consume(token,"reset","Another password 123!"));
        jdbc.update("DELETE FROM app_account_token"); lifecycle.request(email,"reset");
        lifecycle.consume(resetToken,"reset","Another password 123!");
        mvc.perform(get("/api/account").session(session)).andExpect(status().isUnauthorized());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_account_token",Integer.class));
        postJson("/api/auth/login",new MockHttpSession(),Map.of("email",email,"password",password)).andExpect(status().isUnauthorized());
        postJson("/api/auth/login",new MockHttpSession(),Map.of("email",email,"password","Another password 123!")).andExpect(status().isOk());
    }
    @Test void deletionRequiresPasswordAndCascadesPrivateData() throws Exception {
        lifecycle.register(email,"Fixture",password); lifecycle.consume(verification,"verification",null);
        var session=login(); UUID id=accounts.view(((AccountPrincipal)accounts.loadUserByUsername(email)).id()).id();
        jdbc.update("INSERT INTO app_user_resume(user_id,revision,file_name,file_type,file_size,extracted_text) VALUES(?,?,?,?,?,?)",id,UUID.randomUUID(),"fixture.pdf","application/pdf",100,"Synthetic resume for deletion test only.");
        lifecycle.request(email,"reset");
        mvc.perform(delete("/api/account").session(session).header("X-CSRF-TOKEN",csrf(session)).contentType("application/json")
            .content(json.writeValueAsString(Map.of("password","incorrect password","confirmation","EXCLUIR")))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/account").session(session).header("X-CSRF-TOKEN",csrf(session)).contentType("application/json")
            .content(json.writeValueAsString(Map.of("password",password,"confirmation","EXCLUIR")))).andExpect(status().isNoContent());
        for (String table:List.of("app_user","app_user_resume","app_user_preferences","app_account_token"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    }
    @Test void recoveryDoesNotRevealWhetherEmailExistsAndRegistrationRollsBackOnDeliveryFailure() throws Exception {
        lifecycle.register(email,"Fixture",password);
        var first=postJson("/api/auth/password/request",new MockHttpSession(),Map.of("email",email)).andExpect(status().isOk()).andReturn();
        var second=postJson("/api/auth/password/request",new MockHttpSession(),Map.of("email","unknown@example.invalid")).andExpect(status().isOk()).andReturn();
        assertEquals(first.getResponse().getContentAsString(),second.getResponse().getContentAsString());
        doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(mail).send(eq("failed@example.invalid"),anyString(),anyString());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->lifecycle.register("failed@example.invalid","Fixture",password));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email='failed@example.invalid'",Integer.class));
    }
    @Test void disabledDeliveryIsReportedAndDoesNotPretendToSendMail() throws Exception {
        when(mail.enabled()).thenReturn(false);
        mvc.perform(get("/api/auth/options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.emailDeliveryEnabled").value(false));
        postJson("/api/auth/password/request",new MockHttpSession(),Map.of("email",email))
            .andExpect(status().isServiceUnavailable());
        verify(mail,never()).send(anyString(),anyString(),anyString());
    }
}
