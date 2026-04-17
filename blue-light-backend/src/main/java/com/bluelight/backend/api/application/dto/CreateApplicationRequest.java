package com.bluelight.backend.api.application.dto;

import com.bluelight.backend.domain.application.ApplicantType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Create application request DTO
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateApplicationRequest {

    @NotBlank(message = "Address is required")
    @Size(max = 255, message = "Address must be 255 characters or less")
    private String address;

    @NotBlank(message = "Postal code is required")
    @Size(max = 10, message = "Postal code must be 10 characters or less")
    private String postalCode;

    @Size(max = 50, message = "Building type must be 50 characters or less")
    private String buildingType;

    @NotNull(message = "Selected kVA is required")
    @Positive(message = "Selected kVA must be a positive number")
    private Integer selectedKva;

    /**
     * 신청자 유형 (Phase 1 필수)
     * INDIVIDUAL: 개인 / CORPORATE: 법인
     */
    @NotNull(message = "applicantType is required")
    private ApplicantType applicantType;

    /**
     * SP Group 계정 번호 (선택)
     */
    @Size(max = 30, message = "SP Account No must be 30 characters or less")
    private String spAccountNo;

    // ── Phase 18: 갱신 관련 필드 ──

    /**
     * 신청 유형: "NEW" (기본) / "RENEWAL"
     */
    private String applicationType;

    /**
     * 원본 신청 ID (기존 완료 신청 선택 시)
     */
    private Long originalApplicationSeq;

    /**
     * 기존 면허 번호 (직접 입력 시)
     */
    @Size(max = 50, message = "Licence number must be 50 characters or less")
    private String existingLicenceNo;

    /**
     * 기존 면허 만료일 (직접 입력, "yyyy-MM-dd")
     */
    private String existingExpiryDate;

    /**
     * 갱신 기간 (3 or 12 개월, 갱신 시 필수)
     */
    private Integer renewalPeriodMonths;

    /**
     * 갱신 참조 번호
     */
    @Size(max = 50, message = "Renewal reference number must be 50 characters or less")
    private String renewalReferenceNo;

    /**
     * SLD 제출 방식: "SELF_UPLOAD" (기본) / "REQUEST_LEW"
     */
    private String sldOption;

    // ── Phase 2 PR#3: 법인 JIT 회사 정보 ──

    /**
     * applicantType=CORPORATE이고 User.companyName이 없을 때 필수.
     * INDIVIDUAL일 때 전송되어도 무시된다(서비스 계층에서 처리).
     *
     * 필수성 조건부 검증은 {@link #isCompanyInfoValidForCorporate()} 참조.
     * User에 이미 companyName이 저장돼 있는 경우에는 이 필드가 비어있어도 OK.
     */
    @Valid
    private CompanyInfoRequest companyInfo;

    /**
     * Bean Validation 조건부 검증 — applicantType=CORPORATE인데 companyInfo가 누락된 경우를
     * 탐지한다. 단, User에 이미 companyName이 있는 케이스는 여기서 알 수 없으므로 서비스
     * 계층에서 추가 분기한다. 이 단계에서는 "둘 다 누락"만 400으로 떨어뜨리지 말고,
     * DTO 단에서는 applicantType 자체만 체크하여 *완전히 누락된 법인 신청*을 거른다.
     *
     * 실제 "User에 companyName이 없고 + companyInfo도 없음" 분기는 서비스에서 처리:
     * errorCode="COMPANY_INFO_REQUIRED".
     *
     * 여기에는 null/not-null 자체 검증만 수행하지 않는다 — 서비스에서 복합 판단.
     */
    @JsonIgnore
    @AssertTrue(message = "applicantType is required")
    public boolean isApplicantTypePresent() {
        return applicantType != null;
    }
}
