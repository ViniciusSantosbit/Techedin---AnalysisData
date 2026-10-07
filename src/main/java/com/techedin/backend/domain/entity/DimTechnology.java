package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dim_technology")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DimTechnology {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
   @Column(name = "technology_id")
    private Long technologyId;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "category")
    private String category;
}
