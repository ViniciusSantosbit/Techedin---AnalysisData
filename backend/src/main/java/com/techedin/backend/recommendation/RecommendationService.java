package com.techedin.backend.recommendation;

import com.techedin.backend.account.AccountService;
import com.techedin.backend.resume.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class RecommendationService {
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Technology(String name, List<String> aliases) {}
    public record Job(long id, String title, String company, String source, String url,
                      String publishedDate, String firstSeen, String seniority, List<String> technologies) {}
    public record Match(Job job, int relevance, List<String> matchedSkills, List<String> otherSkills, List<String> reasons) {}
    public record Result(String state, UUID resumeRevision, List<String> skills, String targetRole, String seniority,
                         int sampleSize, List<Match> matches) {}
    private final ResumeService resumes;
    private final AccountService accounts;
    private final JdbcTemplate jdbc;
    private final List<Technology> catalog;
    public RecommendationService(ResumeService resumes, AccountService accounts, JdbcTemplate jdbc, ObjectMapper json) throws Exception {
        this.resumes = resumes; this.accounts = accounts; this.jdbc = jdbc;
        try (var stream = new ClassPathResource("technology-catalog.json").getInputStream()) {
            catalog = Arrays.asList(json.readValue(stream, Technology[].class));
        }
    }
    public Result get(UUID user) {
        var resume = resumes.get(user);
        if (resume == null) return new Result("no_resume", null, List.of(), "", "any", 0, List.of());
        var preferences = accounts.view(user).preferences();
        var skills = extract(resume.text());
        var jobs = jobs();
        var matches = rank(skills, preferences, jobs);
        return new Result(skills.isEmpty() ? "no_skills" : "ready", resume.revision(), skills,
            preferences.targetRole(), preferences.seniority(), jobs.size(), matches);
    }
    List<Job> jobs() {
        Map<Long, Job> jobs = new LinkedHashMap<>();
        jdbc.query("""
            SELECT j.job_id, j.title, j.company, j.source, j.source_url, j.published_date, j.first_seen,
                   s.level, t.name AS technology
            FROM (SELECT * FROM dim_job WHERE is_verified = true AND is_demo = false AND source_url IS NOT NULL
                  ORDER BY first_seen DESC, job_id DESC LIMIT 2000) j
            LEFT JOIN fact_job_technology f ON f.job_id=j.job_id
            LEFT JOIN dim_technology t ON t.technology_id=f.technology_id
            LEFT JOIN dim_seniority s ON s.seniority_id=f.seniority_id
            ORDER BY j.first_seen DESC, j.job_id DESC, t.name
            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long id = rs.getLong("job_id");
            Job job = jobs.get(id);
            if (job == null) {
                var date = rs.getDate("published_date");
                job = new Job(id, rs.getString("title"), rs.getString("company"), rs.getString("source"), rs.getString("source_url"),
                    date == null ? null : date.toLocalDate().toString(), rs.getDate("first_seen").toLocalDate().toString(),
                    rs.getString("level") == null ? "Não informado" : rs.getString("level"), new ArrayList<>());
                jobs.put(id, job);
            }
            String skill = rs.getString("technology");
            if (skill != null && !job.technologies().contains(skill)) job.technologies().add(skill);
        });
        return new ArrayList<>(jobs.values());
    }
    public List<String> extract(String text) {
        return catalog.stream().filter(item -> item.aliases().stream().anyMatch(alias -> containsSkill(text, alias)))
            .map(Technology::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    static boolean containsSkill(String text, String alias) {
        if (alias.equalsIgnoreCase("r")) return Pattern.compile("(?i)\\b(?:linguagem|language|programação em)\\s+r\\b|\\bRStudio\\b").matcher(text).find();
        if (alias.equalsIgnoreCase("go")) return Pattern.compile("\\bGo\\b(?!\\s+(?:to|ahead|back)\\b)|(?i:golang|linguagem\\s+go)").matcher(text).find();
        if (alias.equalsIgnoreCase("rest")) return Pattern.compile("\\bREST\\b|(?i:\\brestful\\b|\\bapi\\s+rest\\b)").matcher(text).find();
        return Pattern.compile("(?<![\\p{L}\\p{N}_+#])" + Pattern.quote(alias) + "(?![\\p{L}\\p{N}_+#])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
    static String normalize(String value) { return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT); }
    static List<Match> rank(List<String> skills, AccountService.Preferences preferences, List<Job> jobs) {
        var skillSet = new HashSet<>(skills);
        var levels = Map.of("internship", "estagio", "junior", "junior", "mid", "pleno", "senior", "senior", "specialist", "especialista");
        var roleWords = Arrays.stream(normalize(preferences.targetRole()).split("[^a-z0-9+#]+"))
            .filter(word -> word.length() >= 3 && !Set.of("desenvolvimento", "desenvolvedor", "desenvolvedora", "analista", "engenheiro", "engenheira", "software", "para", "com").contains(word)).toList();
        List<Match> matches = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (Job job : jobs) {
            try { var uri = java.net.URI.create(job.url()); if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) continue; }
            catch (IllegalArgumentException exception) { continue; }
            var common = job.technologies().stream().filter(skillSet::contains).distinct().sorted().toList();
            if (common.isEmpty()) continue;
            String desired = levels.get(preferences.seniority());
            String actual = normalize(job.seniority());
            if (desired != null && !actual.equals("nao informado") && !actual.equals(desired)) continue;
            if (!urls.add(job.url())) continue;
            var missing = job.technologies().stream().filter(skill -> !skillSet.contains(skill)).distinct().sorted().toList();
            int score = (int) Math.round(80.0 * common.size() / (common.size() + missing.size()));
            List<String> reasons = new ArrayList<>();
            reasons.add(common.size() + " de " + (common.size() + missing.size()) + " tecnologias citadas na vaga aparecem no currículo.");
            if (!roleWords.isEmpty() && roleWords.stream().anyMatch(word -> containsSkill(normalize(job.title()), word))) {
                score += 15; reasons.add("O título se relaciona ao cargo desejado.");
            }
            if (desired != null && actual.equals(desired)) { score += 5; reasons.add("Senioridade correspondente à sua preferência."); }
            else if (actual.equals("nao informado")) reasons.add("A vaga não informa senioridade: confira os requisitos.");
            matches.add(new Match(job, score, common, missing, reasons));
        }
        return matches.stream().sorted(Comparator.comparingInt(Match::relevance).reversed()
            .thenComparing(match -> match.job().publishedDate() == null ? match.job().firstSeen() : match.job().publishedDate(), Comparator.reverseOrder())
            .thenComparingLong(match -> match.job().id())).limit(20).toList();
    }
}
