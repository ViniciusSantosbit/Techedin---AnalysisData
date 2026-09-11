package com.techedin.backend.repository;

import com.techedin.backend.domain.entity.FactJobTechnology;
import com.techedin.backend.domain.entity.FactJobTechnologyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FactJobTechnologyRepository extends JpaRepository<FactJobTechnology, FactJobTechnologyId> {

    @Query("""
        SELECT new com.techedin.backend.dto.TechnologyTrendDTO(
            t.name,
            d.year,
            d.month,
            SUM(f.mentionCount)
        )
        FROM FactJobTechnology f
        JOIN f.technology t
        JOIN f.date d
        GROUP BY t.name, d.year, d.month
        ORDER BY d.year DESC, d.month DESC
    """)
    List<com.techedin.backend.dto.TechnologyTrendDTO> findMentionTrends();

    @Query("""
        SELECT new com.techedin.backend.dto.SeniorityAnalyticsDTO(
            s.level,
            t.name,
            SUM(f.mentionCount)
        )
        FROM FactJobTechnology f
        JOIN f.technology t
        JOIN f.seniority s
        GROUP BY s.level, t.name
        ORDER BY s.level, SUM(f.mentionCount) DESC
    """)
    List<com.techedin.backend.dto.SeniorityAnalyticsDTO> findSeniorityAnalytics();

    @Query("""
        SELECT new com.techedin.backend.dto.TechnologyMetricsDTO(
            t.name,
            SUM(f.mentionCount),
            s.level,
            SUM(f.mentionCount)
        )
        FROM FactJobTechnology f
        JOIN f.technology t
        JOIN f.seniority s
        WHERE t.name = :name
        GROUP BY t.name, s.level
        ORDER BY SUM(f.mentionCount) DESC
    """)
    List<com.techedin.backend.dto.TechnologyMetricsDTO> findMetricsByName(@Param("name") String name);
}
