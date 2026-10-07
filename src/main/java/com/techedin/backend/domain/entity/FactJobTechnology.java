package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "fact_job_technology")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FactJobTechnology {

    @EmbeddedId
    private FactJobTechnologyId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("jobId")
    @JoinColumn(name = "job_id", nullable = false)
    private DimJob job;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("technologyId")
    @JoinColumn(name = "technology_id", nullable = false)
    private DimTechnology technology;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("dateId")
    @JoinColumn(name = "date_id", nullable = false)
    private DimDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("seniorityId")
    @JoinColumn(name = "seniority_id", nullable = false)
    private DimSeniority seniority;

    @Column(name = "mention_count", nullable = false)
    private Integer mentionCount;
}
