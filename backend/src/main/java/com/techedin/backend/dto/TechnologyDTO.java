package com.techedin.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnologyDTO {
    private Long technologyId;
    private String name;
    private String category;
}
