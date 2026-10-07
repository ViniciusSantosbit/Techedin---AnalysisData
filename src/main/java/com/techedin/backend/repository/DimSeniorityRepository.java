package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.DimSeniority;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DimSeniorityRepository extends JpaRepository<DimSeniority, Long> {
    Optional<DimSeniority> findByLevelIgnoreCase(String level);
}
