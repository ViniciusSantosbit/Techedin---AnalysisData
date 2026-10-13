package com.techedin.backend.controller;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional local runner. No shell or caller-controlled command arguments. */
@RestController
@RequestMapping("/api/admin/scraper")
@ConditionalOnProperty(name = "app.scraper.enabled", havingValue = "true")
public class ScraperAdminController {
    private final String token;
    private final String python;
    private final Path script;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String state = "idle";
    private volatile Process process;

    public ScraperAdminController(@Value("${app.scraper.token:}") String token,
            @Value("${app.scraper.python:python}") String python,
            @Value("${app.scraper.script:../ingestion/scrapers/job_scraper.py}") String script) {
        if (token.length() < 32) throw new IllegalArgumentException("SCRAPER_ADMIN_TOKEN deve ter pelo menos 32 caracteres");
        this.token = token;
        this.python = python;
        this.script = Path.of(script).toAbsolutePath().normalize();
    }
    private boolean authorized(String candidate) {
        return candidate != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
    @PostMapping("/trigger")
    public ResponseEntity<Map<String,String>> trigger(@RequestHeader(value="X-Admin-Token", required=false) String candidate) {
        if (!authorized(candidate)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!running.compareAndSet(false, true)) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "running"));
        state = "running";
        executor.submit(() -> {
            try {
                process = new ProcessBuilder(python, script.toString(), "--max-jobs", "10")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!process.waitFor(5, TimeUnit.MINUTES)) { stopProcess(); state = "timeout"; }
                else state = process.exitValue() == 0 ? "completed" : "failed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); stopProcess(); state = "interrupted";
            } catch (Exception e) { state = "failed"; }
            finally { running.set(false); }
        });
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }
    @GetMapping("/status")
    public ResponseEntity<Map<String,String>> status(@RequestHeader(value="X-Admin-Token", required=false) String candidate) {
        if (!authorized(candidate)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(Map.of("status", state));
    }
    private void stopProcess() {
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
    @PreDestroy void close() { executor.shutdownNow(); stopProcess(); }
}
