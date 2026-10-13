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

    @Column(name = "source_url")
    private String sourceUrl;
    @Column(name = "published_date")
    private java.time.LocalDate publishedDate;
    @Column(name = "first_seen", nullable = false)
    private java.time.LocalDate firstSeen;
    @Column(name = "is_demo", nullable = false)
    private boolean demo;
    @Column(name = "is_verified", nullable = false)
    private boolean verified;
}
