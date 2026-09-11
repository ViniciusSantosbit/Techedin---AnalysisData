package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.DimTechnology;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DimTechnologyRepository extends JpaRepository<DimTechnology, Long> {
    Optional<DimTechnology> findByNameIgnoreCase(String name);
}
