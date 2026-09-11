package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dim_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DimJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "company", nullable = false)
    private String company;

    @Column(name = "description")
    private String description;

    @Column(name = "source", nullable = false)
    private String source;
}
