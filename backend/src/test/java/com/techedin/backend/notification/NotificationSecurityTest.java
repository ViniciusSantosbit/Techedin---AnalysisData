package com.techedin.backend.notification;
import com.techedin.backend.account.AccountPrincipal;
import com.techedin.backend.recommendation.RecommendationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:push;MODE=PostgreSQL;NON_KEYWORDS=DAY,MONTH,YEAR,DATE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "ACCOUNT_REQUIRE_VERIFICATION=false", "spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create-drop","spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:db/migration/V4__accounts_and_preferences.sql","WEB_PUSH_ENABLED=false"})
@AutoConfigureMockMvc
class NotificationSecurityTest {
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired NotificationService service;
    @MockitoBean WebPushSender sender;@MockitoBean RecommendationService recommendations;
    UUID first=UUID.randomUUID(),second=UUID.randomUUID();
    String endpoint="https://fcm.googleapis.com/fcm/send/fixture";
    String body(){return "{\"endpoint\":\""+endpoint+"\"}";}
    @BeforeAll static void schema(@Autowired JdbcTemplate jdbc)throws Exception {
        for(String name:List.of("V5__private_resume_text.sql","V6__private_push_subscriptions.sql","V7__account_verification_and_recovery.sql"))
            jdbc.execute(new ClassPathResource("db/migration/"+name).getContentAsString(StandardCharsets.UTF_8).split("-- PostgreSQL security:")[0]);
    }
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM app_user");jdbc.update("DELETE FROM dim_job");
        reset(sender,recommendations);when(sender.configured()).thenReturn(true);when(sender.publicKey()).thenReturn("fixture");
        for(UUID id:List.of(first,second)) {
            jdbc.update("INSERT INTO app_user(user_id,email,display_name,password_hash) VALUES(?,?,?,?)",id,id+"@example.invalid","Fixture","unused");
            jdbc.update("INSERT INTO app_user_resume(user_id,revision,file_name,file_type,file_size,extracted_text) VALUES(?,?,?,?,?,?)",id,UUID.randomUUID(),"cv.pdf","application/pdf",100,"Currículo sintético com experiência em Java e SQL para testes isolados.");
        }
        job(100);
    }
    void job(long id){jdbc.update("INSERT INTO dim_job(job_id,title,company,source,source_url,first_seen,is_verified,is_demo,discovered_at) VALUES(?,?,?,?,?,?,?,?,?)",id,"Java","Fixture","Fixture","https://jobs.example/"+id,LocalDate.now(),true,false,OffsetDateTime.now());}
    void subscribe(UUID id)throws Exception {mvc.perform(post("/api/account/notifications").with(user(new AccountPrincipal(id,"fixture@example.invalid",null))).with(csrf()).contentType("application/json").content(body())).andExpect(status().isOk());}
    void ready(){jdbc.update("UPDATE app_push_subscription SET retry_after=?",OffsetDateTime.now().minusMinutes(1));}
    void compatible() {
        var job=new RecommendationService.Job(101,"Java","Fixture","Fixture","https://jobs.example/101",null,"2026-10-16","Júnior",List.of("Java"));
        when(recommendations.matchesBetween(eq(first),anyLong(),anyLong(),any())).thenReturn(List.of(new RecommendationService.Match(job,80,List.of("Java"),List.of(),List.of("Em comum"))));
    }
    @Test void requiresAuthenticationCsrfAndOwnerForRemoval()throws Exception {
        mvc.perform(get("/api/account/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/notifications").with(csrf()).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/account/notifications").with(user(new AccountPrincipal(first,"fixture@example.invalid",null))).contentType("application/json").content(body())).andExpect(status().isForbidden());
        subscribe(first);subscribe(first);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM app_push_subscription",Integer.class));
        mvc.perform(delete("/api/account/notifications").with(user(new AccountPrincipal(second,"other@example.invalid",null))).with(csrf()).contentType("application/json").content(body())).andExpect(status().isNoContent());
        assertTrue(service.subscribed(first,endpoint));assertFalse(service.subscribed(second,endpoint));
        mvc.perform(post("/api/account/notifications").with(user(new AccountPrincipal(second,"other@example.invalid",null))).with(csrf()).contentType("application/json").content(body())).andExpect(status().isConflict());
    }
    @Test void noRetroactiveAlertsAndNoRepeatAfterSuccessfulDelivery()throws Exception {
        subscribe(first);service.process();verify(sender,never()).send(any());
        assertEquals(100L,jdbc.queryForObject("SELECT cursor_job_id FROM app_push_subscription",Long.class));
        job(101);ready();compatible();when(sender.send(endpoint)).thenReturn(201);
        service.process();ready();service.process();verify(sender,times(1)).send(endpoint);
        assertEquals(101L,jdbc.queryForObject("SELECT cursor_job_id FROM app_push_subscription",Long.class));
    }
    @Test void retriesThenRechecksMatchingAndRemovesExpiredEndpoints()throws Exception {
        subscribe(first);job(101);compatible();when(sender.send(endpoint)).thenReturn(503);
        service.process();assertEquals(1,jdbc.queryForObject("SELECT attempts FROM app_push_subscription",Integer.class));
        when(recommendations.matchesBetween(eq(first),anyLong(),anyLong(),any())).thenReturn(List.of());ready();service.process();
        verify(sender,times(1)).send(endpoint);
        job(102);ready();compatible();when(sender.send(endpoint)).thenReturn(410);service.process();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM app_push_subscription",Integer.class));
    }
}
