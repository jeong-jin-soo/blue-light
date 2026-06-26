package com.bluelight.backend.api.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 라이선스 PDF 파싱 결과 (AI 서비스 추출값 → 프론트 프리필).
 * 추출 못한 필드는 null — LEW 가 검토·수정한다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LicenseParseResponse {
    private String licenseNumber;
    /** ISO YYYY-MM-DD (발급일). */
    private String issueDate;
    /** ISO YYYY-MM-DD (만료일). */
    private String expiryDate;
}
