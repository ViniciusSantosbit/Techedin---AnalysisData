package com.techedin.backend.resume;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class CareerChatServiceTest {
    @Test void rateLimitIsPerAccount() {
        var service = new CareerChatService(mock(ResumeService.class), new ObjectMapper(), "", "model");
        UUID user = UUID.randomUUID();
        for (int i=0;i<5;i++) service.limit(user);
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.limit(user)).getStatusCode().value());
        assertDoesNotThrow(() -> service.limit(UUID.randomUUID()));
    }
    @Test void missingKeyDoesNotReadResumeAndContextUsesAuthenticatedOwner() {
        var resumes = mock(ResumeService.class);
        UUID user = UUID.randomUUID();
        var question = new CareerChatController.Question("Como melhorar?", List.of(), null);
        var disabled = new CareerChatService(resumes, new ObjectMapper(), "", "model");
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> disabled.ask(user, question)).getStatusCode().value());
        verifyNoInteractions(resumes);
        var enabled = new CareerChatService(resumes, new ObjectMapper(), "fixture", "model");
        assertNull(enabled.context(user).revision());
        UUID revision = UUID.randomUUID();
        when(resumes.get(user)).thenReturn(new ResumeService.ResumeView(revision, "cv.pdf", "application/pdf", 50, "Projetos com Java e SQL", null, null));
        assertEquals(revision, enabled.context(user).revision());
        assertTrue(enabled.context(user).text().contains("Java e SQL"));
        UUID other = UUID.randomUUID();
        assertFalse(enabled.context(other).text().contains("Java e SQL"));
        when(resumes.get(user)).thenReturn(null);
        assertNull(enabled.context(user).revision());
    }
}
