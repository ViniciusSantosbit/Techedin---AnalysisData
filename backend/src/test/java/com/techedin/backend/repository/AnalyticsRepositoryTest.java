package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.*;
import com.techedin.backend.controller.DashboardController;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.datasource.url=jdbc:h2:mem:analytics;NON_KEYWORDS=DAY,MONTH,YEAR,DATE",
    "spring.datasource.driver-class-name=org.h2.Driver"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnalyticsRepositoryTest {
    @Autowired TestEntityManager em;
    @Autowired FactJobTechnologyRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Test void aggregatesOnlyVerifiedRealJobsAndMatchesCaseInsensitiveName() {
        var tech = em.persist(DimTechnology.builder().name("Python").category("Linguagem").build());
        var day = em.persist(DimDate.builder().date(LocalDate.of(2026,10,12)).day(12).month(10).year(2026).build());
        var level = em.persist(DimSeniority.builder().level("Sênior").build());
        addFact(tech, day, level, false, true, 2);
        addFact(tech, day, level, true, true, 100);
        addFact(tech, day, level, false, false, 100);
        em.flush(); em.clear();
        assertEquals(2L, repository.findMetricsByName("python").getFirst().getTotalMentions());
        assertEquals(2L, repository.findTemporalTrends().getFirst().mentionCount());
        assertEquals(2L, repository.findSeniorityAnalytics().getFirst().getMentionCount());
        assertEquals(2L, repository.findMentionTrends().getFirst().getMentionCount());
        var payload = new DashboardController(jdbc).dashboard();
        assertEquals("real", payload.get("dataKind"));
        assertEquals(1, ((List<?>) payload.get("jobs")).size());
    }
    private void addFact(DimTechnology tech, DimDate day, DimSeniority level, boolean demo, boolean verified, int count) {
        var job = em.persist(DimJob.builder().title("Dev").company("Empresa").source("test")
            .sourceUrl("https://example.com/jobs/" + demo + "-" + verified)
            .firstSeen(day.getDate()).demo(demo).verified(verified).build());
        var id = new FactJobTechnologyId(job.getJobId(), tech.getTechnologyId(), day.getDateId(), level.getSeniorityId());
        em.persist(FactJobTechnology.builder().id(id).job(job).technology(tech).date(day)
            .seniority(level).mentionCount(count).build());
    }
}
