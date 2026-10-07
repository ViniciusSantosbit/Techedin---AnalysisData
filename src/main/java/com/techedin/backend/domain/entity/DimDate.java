package com.techedin.backend.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "dim_date")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DimDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "date_id")
    private Long dateId;

    @Column(name = "date", nullable = false, unique = true)
    private java.time.LocalDate date;

    @Column(name = "day", nullable = false)
    private Integer day;

    @Column(name = "month", nullable = false)
    private Integer month;

    @Column(name = "year", nullable = false)
    private Integer year;
}
