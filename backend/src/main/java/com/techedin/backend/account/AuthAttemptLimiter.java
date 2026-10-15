package com.techedin.backend.account;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bounded per-process throttle. Only the direct peer address is trusted. */
@Component
public class AuthAttemptLimiter {
    private record Window(long start, int attempts) {}
    private final Map<String, Window> windows = new HashMap<>();
    public synchronized void check(String peer, boolean registration) {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= 600);
        String key = (registration ? "register:" : "login:") + peer;
        Window window = windows.getOrDefault(key, new Window(now, 0));
        if (windows.size() >= 10000 || window.attempts() >= (registration ? 10 : 30)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Aguarde alguns minutos.");
        }
        windows.put(key, new Window(window.start(), window.attempts() + 1));
    }
}
