package com.techedin.backend.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;

/** Bounded job sample with public source links and technology membership. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;
    public DashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> dashboard() {
        Map<Long, Map<String, Object>> jobs = new LinkedHashMap<>();
        Map<String, Integer> sourceCounts = new TreeMap<>();
        jdbc.query("""
            SELECT j.job_id, j.title, j.company, j.source, j.source_url,
                   j.published_date, j.first_seen, s.level, t.name AS technology
            FROM (SELECT * FROM dim_job WHERE is_verified = true AND is_demo = false
                  AND source_url IS NOT NULL ORDER BY first_seen DESC, job_id DESC LIMIT 2000) j
            LEFT JOIN fact_job_technology f ON f.job_id = j.job_id
            LEFT JOIN dim_seniority s ON s.seniority_id = f.seniority_id
            LEFT JOIN dim_technology t ON t.technology_id = f.technology_id
            ORDER BY j.first_seen DESC, j.job_id DESC, t.name
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long id = rs.getLong("job_id");
            Map<String, Object> job = jobs.get(id);
            if (job == null) {
                job = new LinkedHashMap<>();
                job.put("title", rs.getString("title"));
                job.put("company", rs.getString("company"));
                job.put("source", rs.getString("source"));
                job.put("source_url", rs.getString("source_url"));
                var published = rs.getDate("published_date");
                job.put("published_date", published == null ? null : published.toLocalDate().toString());
                job.put("first_seen", rs.getDate("first_seen").toLocalDate().toString());
                job.put("seniority", rs.getString("level") == null ? "Não informado" : rs.getString("level"));
                job.put("is_demo", false);
                job.put("technologies", new ArrayList<String>());
                jobs.put(id, job);
                sourceCounts.merge(rs.getString("source"), 1, Integer::sum);
            }
            String technology = rs.getString("technology");
            if (technology != null) {
                @SuppressWarnings("unchecked") List<String> names = (List<String>) job.get("technologies");
                if (!names.contains(technology)) names.add(technology);
            }
        });
        var sources = sourceCounts.entrySet().stream().map(entry -> Map.of(
            "name", entry.getKey(), "status", "stored", "jobCount", entry.getValue())).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("dataKind", "real");
        payload.put("collectedAt", null); // first_seen is not the timestamp of the latest collection.
        payload.put("sources", sources);
        payload.put("jobs", new ArrayList<>(jobs.values()));
        return payload;
    }
}
