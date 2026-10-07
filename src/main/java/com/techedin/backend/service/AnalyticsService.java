package com.techedin.backend.service;

import com.techedin.backend.dto.SeniorityAnalyticsDTO;
import com.techedin.backend.repository.FactJobTechnologyRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnalyticsService {

    private final FactJobTechnologyRepository factJobTechnologyRepository;

    public AnalyticsService(FactJobTechnologyRepository factJobTechnologyRepository) {
        this.factJobTechnologyRepository = factJobTechnologyRepository;
    }

    public List<SeniorityAnalyticsDTO> getSeniorityAnalytics() {
        return factJobTechnologyRepository.findSeniorityAnalytics();
    }
}
