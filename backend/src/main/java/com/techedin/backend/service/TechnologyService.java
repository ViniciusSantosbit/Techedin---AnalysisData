package com.techedin.backend.service;

import com.techedin.backend.domain.entity.DimTechnology;
import com.techedin.backend.repository.DimTechnologyRepository;
import com.techedin.backend.dto.TechnologyDTO;
import com.techedin.backend.dto.TechnologyTrendDTO;
import com.techedin.backend.dto.TechnologyMetricsDTO;
import com.techedin.backend.dto.TrendDTO;
import com.techedin.backend.repository.FactJobTechnologyRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TechnologyService {

    private final DimTechnologyRepository dimTechnologyRepository;
    private final FactJobTechnologyRepository factJobTechnologyRepository;

    public TechnologyService(DimTechnologyRepository dimTechnologyRepository,
                             FactJobTechnologyRepository factJobTechnologyRepository) {
        this.dimTechnologyRepository = dimTechnologyRepository;
        this.factJobTechnologyRepository = factJobTechnologyRepository;
    }

    public List<TechnologyDTO> getAllTechnologies() {
        return dimTechnologyRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public Optional<TechnologyDTO> getTechnologyByName(String name) {
        return dimTechnologyRepository.findByNameIgnoreCase(name)
                .map(this::toDto);
    }

    public List<TechnologyTrendDTO> getTrends() {
        return factJobTechnologyRepository.findMentionTrends();
    }

    public List<TrendDTO> getTechnologyTrends() {
        return factJobTechnologyRepository.findTemporalTrends();
    }

    public List<TechnologyMetricsDTO> getMetricsByName(String name) {
        return factJobTechnologyRepository.findMetricsByName(name);
    }

    private TechnologyDTO toDto(DimTechnology entity) {
        return new TechnologyDTO(
                entity.getTechnologyId(),
                entity.getName(),
                entity.getCategory()
        );
    }
}
