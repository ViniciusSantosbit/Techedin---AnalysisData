package com.techedin.backend.controller;

import com.techedin.backend.dto.TechnologyDTO;
import com.techedin.backend.dto.TechnologyMetricsDTO;
import com.techedin.backend.dto.TechnologyTrendDTO;
import com.techedin.backend.service.TechnologyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/technologies")
public class TechnologyController {

    private final TechnologyService technologyService;

    public TechnologyController(TechnologyService technologyService) {
        this.technologyService = technologyService;
    }

    @GetMapping
    public ResponseEntity<List<TechnologyDTO>> listTechnologies() {
        return ResponseEntity.ok(technologyService.getAllTechnologies());
    }

    @GetMapping("/trends")
    public ResponseEntity<List<TechnologyTrendDTO>> getTrends() {
        return ResponseEntity.ok(technologyService.getTrends());
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getTechnologyMetrics(@PathVariable String name) {
        Optional<TechnologyDTO> technology = technologyService.getTechnologyByName(name);
        if (technology.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<TechnologyMetricsDTO> metrics = technologyService.getMetricsByName(name);
        return ResponseEntity.ok(metrics);
    }
}
