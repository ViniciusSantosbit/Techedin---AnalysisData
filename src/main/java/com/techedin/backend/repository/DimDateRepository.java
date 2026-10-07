package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.DimDate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface DimDateRepository extends JpaRepository<DimDate, Long> {
    Optional<DimDate> findByDate(LocalDate date);
}
