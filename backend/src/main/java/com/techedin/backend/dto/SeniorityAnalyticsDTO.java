package com.techedin.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeniorityAnalyticsDTO {
    private String seniorityLevel;
    private String technologyName;
    private Long mentionCount;
}
