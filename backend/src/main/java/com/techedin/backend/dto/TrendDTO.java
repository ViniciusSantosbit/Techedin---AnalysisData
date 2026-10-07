package com.techedin.backend.dto;

import java.time.LocalDate;

public record TrendDTO(
    LocalDate date,
    String technologyName,
    Long mentionCount
) {
}