package com.techedin.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnologyTrendDTO {
    private String technologyName;
    private Integer year;
    private Integer month;
    private Long mentionCount;
}
