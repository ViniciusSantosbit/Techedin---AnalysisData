package com.techedin.backend.controller;

import com.techedin.backend.dto.TechnologyDTO;
import com.techedin.backend.dto.TechnologyMetricsDTO;
import com.techedin.backend.dto.TechnologyTrendDTO;
import com.techedin.backend.dto.TrendDTO;
import com.techedin.backend.service.TechnologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/technologies")

public class TechnologyController {

    private static final Logger log = LoggerFactory.getLogger(TechnologyController.class);

    private final TechnologyService technologyService;

    public TechnologyController(TechnologyService technologyService) {
        this.technologyService = technologyService;
    }

    @GetMapping
    public ResponseEntity<List<TechnologyDTO>> listTechnologies() {
        try {
            List<TechnologyDTO> technologies = technologyService.getAllTechnologies();
            log.info("GET /api/technologies - Returning {} technologies", technologies.size());
            return ResponseEntity.ok(technologies);
        } catch (Exception e) {
            log.error("Erro ao listar tecnologias: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/trends")
    public ResponseEntity<List<TrendDTO>> getTrends() {
        try {
            List<TrendDTO> trends = technologyService.getTechnologyTrends();
            log.info("GET /api/technologies/trends - Returning {} trends", trends.size());
            return ResponseEntity.ok(trends);
        } catch (Exception e) {
            log.error("Erro ao buscar tendências: ", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<?> getTechnologyMetrics(@PathVariable String name) {
        try {
            Optional<TechnologyDTO> technology = technologyService.getTechnologyByName(name);
            if (technology.isEmpty()) {
                log.warn("Tecnologia não encontrada: {}", name);
                return ResponseEntity.notFound().build();
            }

            List<TechnologyMetricsDTO> metrics = technologyService.getMetricsByName(name);
            log.info("GET /api/technologies/{} - Returning {} metrics", name, metrics.size());
            return ResponseEntity.ok(metrics);
        } catch (Exception e) {
            log.error("Erro ao buscar métricas para tecnologia {}: ", name, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
