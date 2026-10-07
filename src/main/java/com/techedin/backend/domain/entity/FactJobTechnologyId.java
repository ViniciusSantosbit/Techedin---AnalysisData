package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FactJobTechnologyId implements java.io.Serializable {

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "technology_id")
    private Long technologyId;

    @Column(name = "date_id")
    private Long dateId;

    @Column(name = "seniority_id")
    private Long seniorityId;
}
