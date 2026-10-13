package com.techedin.backend.service;

import com.techedin.backend.dto.TechnologyMetricsDTO;
import com.techedin.backend.repository.DimTechnologyRepository;
import com.techedin.backend.repository.FactJobTechnologyRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnologyServiceTest {
    @Test void sumsAllSenioritiesAndKeepsTopCount() {
        var facts = mock(FactJobTechnologyRepository.class);
        when(facts.findMetricsByName("python")).thenReturn(List.of(
            new TechnologyMetricsDTO("Python", 7L, "Sênior", 7L),
            new TechnologyMetricsDTO("Python", 3L, "Júnior", 3L)));
        var service = new TechnologyService(mock(DimTechnologyRepository.class), facts);
        var metrics = service.getMetricsByName("python").getFirst();
        assertEquals(10L, metrics.getTotalMentions());
        assertEquals(7L, metrics.getTopSeniorityMentions());
        assertEquals("Sênior", metrics.getTopSeniority());
    }
    @Test void technologyWithoutJobsHasZeroMetrics() {
        var facts = mock(FactJobTechnologyRepository.class);
        when(facts.findMetricsByName("Python")).thenReturn(List.of());
        var service = new TechnologyService(mock(DimTechnologyRepository.class), facts);
        var metrics = service.getMetricsByName("Python").getFirst();
        assertEquals(0L, metrics.getTotalMentions());
        assertNull(metrics.getTopSeniority());
    }
}
