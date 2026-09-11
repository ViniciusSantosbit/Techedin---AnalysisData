package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dim_seniority")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DimSeniority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seniority_id")
    private Long seniorityId;

    @Column(name = "level", nullable = false, unique = true)
    private String level;
}
