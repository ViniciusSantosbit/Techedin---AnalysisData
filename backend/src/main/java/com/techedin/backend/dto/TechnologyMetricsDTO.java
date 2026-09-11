package com.techedin.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnologyMetricsDTO {
    private String name;
    private Long totalMentions;
    private String topSeniority;
    private Long topSeniorityMentions;
}
