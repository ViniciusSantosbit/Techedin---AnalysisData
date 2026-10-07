package com.techedin.backend.controller;

import com.techedin.backend.dto.SeniorityAnalyticsDTO;
import com.techedin.backend.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/seniority")
    public ResponseEntity<List<SeniorityAnalyticsDTO>> getSeniorityAnalytics() {
        return ResponseEntity.ok(analyticsService.getSeniorityAnalytics());
    }
}
