package com.bluelight.backend.api.breach;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 데이터 유출 보고 요청 DTO
 */
@Getter
@Setter
public class DataBreachRequest {

    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Description is required")
    private String description;

    private String severity;

    private Integer affectedCount;

    private String dataTypesAffected;

    private String containmentActions;
}
