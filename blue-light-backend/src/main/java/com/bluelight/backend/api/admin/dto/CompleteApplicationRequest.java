package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Complete application and issue licence request DTO
 */
@Getter
@NoArgsConstructor
public class CompleteApplicationRequest {

    @NotBlank(message = "License number is required")
    private String licenseNumber;

    @NotNull(message = "License expiry date is required")
    private LocalDate licenseExpiryDate;

    /**
     * 라이선스 발급일 (LEW 가 라이선스 PDF 에서 확인/수정). null 이면 발급 처리 시각으로 기록.
     * SLD 미제출 리마인더의 발급-경과 기준으로 쓰인다.
     */
    private LocalDate licenseIssuedDate;
}
