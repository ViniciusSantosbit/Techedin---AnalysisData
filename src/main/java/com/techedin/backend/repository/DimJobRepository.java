package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.DimJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimJobRepository extends JpaRepository<DimJob, Long> {
}
