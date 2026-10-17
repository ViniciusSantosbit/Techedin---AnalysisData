package com.techedin.backend.resume;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeminiFailureTest {
    @Test void distinguishesProviderQuotaCredentialsModelsAndOutages() {
        assertEquals(429, GeminiFailure.fromStatus(429).getStatusCode().value());
        assertTrue(GeminiFailure.fromStatus(429).getReason().contains("cota"));
        for (int status : new int[]{401, 403}) {
            var failure = GeminiFailure.fromStatus(status);
            assertEquals(503, failure.getStatusCode().value());
            assertTrue(failure.getReason().contains("GEMINI_API_KEY"));
        }
        assertTrue(GeminiFailure.fromStatus(404).getReason().contains("GEMINI_MODEL"));
        assertEquals(503, GeminiFailure.fromStatus(503).getStatusCode().value());
        assertEquals(502, GeminiFailure.fromStatus(400).getStatusCode().value());
    }
}
