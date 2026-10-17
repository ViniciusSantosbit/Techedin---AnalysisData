package com.techedin.backend.recommendation;
import com.techedin.backend.account.AccountService;
import com.techedin.backend.resume.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RecommendationServiceTest {
    RecommendationService.Job job(long id, String url, String title, String level, String... skills) {
        return new RecommendationService.Job(id,title,"Empresa","Origem",url,"2026-10-16","2026-10-16",level,List.of(skills));
    }
    @Test void recognizesCatalogAliasesWithoutSubstringFalsePositives() throws Exception {
        var service = new RecommendationService(mock(ResumeService.class),mock(AccountService.class),mock(JdbcTemplate.class),new ObjectMapper());
        assertTrue(service.extract("Projetos em Java, React e Node.js.").containsAll(List.of("Java","React","Node.js")));
        assertFalse(service.extract("JavaScript, R$ 2000; we go to work; interest.").contains("Java"));
        assertFalse(RecommendationService.containsSkill("JavaScript", "Java"));
        assertFalse(RecommendationService.containsSkill("Restaurante", "REST"));
        assertFalse(RecommendationService.containsSkill("we go to work", "go"));
    }
    @Test void ranksExplainsFiltersSeniorityAndDeduplicates() {
        var preferences = new AccountService.Preferences("backend","","any","junior");
        var jobs = List.of(job(1,"https://jobs.example/1","Backend Java","Júnior","Java","SQL"),
            job(2,"https://jobs.example/2","Backend Java","Sênior","Java"),
            job(3,"https://jobs.example/3","Python","Júnior","Python"),
            job(4,"javascript:alert(1)","Backend Java","Júnior","Java"),
            job(5,"https://jobs.example/1","Backend Java","Júnior","Java","SQL"),
            job(6,"https://jobs.example/6","Java","Não informado","Java","Docker","SQL"));
        var result = RecommendationService.rank(List.of("Java","SQL"), preferences,jobs);
        assertEquals(2,result.size()); assertEquals(1,result.getFirst().job().id());
        assertEquals(100,result.getFirst().relevance());
        assertTrue(result.get(1).otherSkills().contains("Docker"));
        assertTrue(result.getFirst().reasons().stream().anyMatch(reason -> reason.contains("Senioridade")));
    }
    @Test void missingResumeDoesNotReadOtherUsersOrQueryJobs() throws Exception {
        var resumes=mock(ResumeService.class);var accounts=mock(AccountService.class);var jdbc=mock(JdbcTemplate.class);
        var service=new RecommendationService(resumes,accounts,jdbc,new ObjectMapper());UUID user=UUID.randomUUID();
        assertEquals("no_resume",service.get(user).state());verify(resumes).get(user);verifyNoInteractions(accounts,jdbc);
    }
}
