package com.techedin.backend.controller;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScraperAdminControllerTest {
    @Test void rejectsMissingAndIncorrectTokensWithoutStartingAProcess() {
        var controller = new ScraperAdminController("a".repeat(32), "python", "missing.py");
        try {
            assertEquals(401, controller.trigger(null).getStatusCode().value());
            assertEquals(401, controller.trigger("wrong").getStatusCode().value());
            assertEquals(401, controller.status(null).getStatusCode().value());
            assertEquals("idle", controller.status("a".repeat(32)).getBody().get("status"));
        } finally { controller.close(); }
    }
    @Test void rejectsWeakConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new ScraperAdminController("short", "python", "missing.py"));
    }
}
