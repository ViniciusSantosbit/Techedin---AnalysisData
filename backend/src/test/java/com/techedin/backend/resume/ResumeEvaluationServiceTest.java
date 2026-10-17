package com.techedin.backend.resume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeEvaluationServiceTest {
    final ObjectMapper json = new ObjectMapper();
    String result(double score) {
        String item = "{\"name\":\"ignored\",\"score\":" + score + ",\"feedback\":\"Melhore as evidÃªncias dos projetos.\"}";
        return "{\"criteria\":[" + String.join(",", java.util.Collections.nCopies(5, item)) + "],\"suggestions\":\"Descreva resultados verificÃ¡veis.\"}";
    }
    @Test void computesScoreFromFiveBoundedCriteria() throws Exception {
        var revision = UUID.randomUUID();
        var evaluation = ResumeEvaluationService.parse(revision, json.readTree(result(1.5)));
        assertEquals(7.5, evaluation.score());
        assertEquals(revision, evaluation.revision());
        assertEquals("Clareza", evaluation.criteria().getFirst().name());
    }
    @Test void rejectsInventedOutOfRangeScoreAndMissingCriteria() throws Exception {
        assertThrows(ResponseStatusException.class, () -> ResumeEvaluationService.parse(UUID.randomUUID(), json.readTree(result(8))));
        assertThrows(ResponseStatusException.class, () -> ResumeEvaluationService.parse(UUID.randomUUID(), json.readTree("{}")));
    }
    @Test void missingKeyAndStaleRevisionNeverCallProvider() {
        var resumes = mock(ResumeService.class);
        UUID user = UUID.randomUUID(), revision = UUID.randomUUID();
        when(resumes.get(user)).thenReturn(new ResumeService.ResumeView(revision, "cv.pdf", "application/pdf", 100, "Texto sintÃ©tico", null, null));
        var service = new ResumeEvaluationService(resumes, json, "", "gemini-3.8-flash");
        assertFalse(service.configured());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.evaluate(user, revision)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.evaluate(user, UUID.randomUUID())).getStatusCode().value());
    }
}
