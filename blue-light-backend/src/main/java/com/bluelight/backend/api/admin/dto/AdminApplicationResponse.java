package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.LicenseStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin application response DTO (includes applicant info)
 */
@Getter
@Builder
public class AdminApplicationResponse {

    private Long applicationSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private BigDecimal quoteAmount;
    private ApplicationStatus status;
    /** 발급된 라이선스의 유효성 — 신청 상태와 분리(ACTIVE/EXPIRED, 발급 전 null). */
    private LicenseStatus licenseStatus;
    private String licenseNumber;
    private LocalDate licenseExpiryDate;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Applicant info
    private Long userSeq;
    private String userFirstName;
    private String userLastName;
    private String userEmail;
    private String userPhone;
    private String userCompanyName;
    private String userUen;
    private String userDesignation;
    private String userCorrespondenceAddress;
    private String userCorrespondencePostalCode;

    // Assigned LEW info
    private Long assignedLewSeq;
    private String assignedLewFirstName;
    private String assignedLewLastName;
    private String assignedLewEmail;
    private String assignedLewLicenceNo;
    private String assignedLewGrade;
    private Integer assignedLewMaxKva;
    /** 파생: 배정 LEW 등급이 현재 selectedKva 를 처리할 수 없으면 true (재배정 필요 경고 플래그, #5). */
    private boolean assignedLewGradeMismatch;

    // SP Group 계정 번호
    private String spAccountNo;

    // ── 갱신 + 견적 필드 ──
    private String applicationType;
    private BigDecimal sldFee;
    private BigDecimal calloutFee;
    private Long originalApplicationSeq;
    private String existingLicenceNo;
    private String renewalReferenceNo;
    private LocalDate existingExpiryDate;
    private Integer renewalPeriodMonths;
    private BigDecimal emaFee;

    // SLD 제출 방식
    private String sldOption;

    // LOA 서명 정보
    private String loaSignatureUrl;
    private LocalDateTime loaSignedAt;

    // ── Phase 5: kVA 확정 상태 ──
    private String kvaStatus;           // UNKNOWN | CONFIRMED
    private String kvaSource;           // USER_INPUT | LEW_VERIFIED | null
    private Long kvaConfirmedBy;
    private LocalDateTime kvaConfirmedAt;

    // ── EMA ELISE 제출 추적 (ema-submission-tracking-spec.md §7 inline) ──
    // 목록/상세에서 EMA 상태 배지·필터를 그릴 수 있도록 엔티티에서 직접 도출 가능한 필드만 inline.
    // 파일 존재 여부(emaAckPresent/licensePdfPresent)·canComplete 등 repository 조회가 필요한 필드는
    // 전용 응답 {@code EmaSubmissionResponse}(GET /ema)에서 제공 — 목록 N+1 회피.
    private String emaSubmissionStatus; // NOT_SUBMITTED | SUBMITTED | QUERY_RAISED | RESUBMITTED | APPROVED | REJECTED | WITHDRAWN
    private LocalDateTime emaSubmittedAt;
    private String emaReferenceNo;
    private Long emaSubmittedByUserSeq;
    private LocalDateTime emaDecisionAt;
    private String emaQueryNote;
    private boolean emaGrandfathered;   // 허점#2 — backfill APPROVED legacy 건 식별 (status==APPROVED && decisionAt==null && referenceNo==null)

    public static AdminApplicationResponse from(Application application) {
        return AdminApplicationResponse.builder()
                .applicationSeq(application.getApplicationSeq())
                .address(application.getAddress())
                .postalCode(application.getPostalCode())
                .buildingType(application.getBuildingType())
                .selectedKva(application.getSelectedKva())
                .quoteAmount(application.getQuoteAmount())
                .status(application.getStatus())
                .licenseStatus(application.getLicenseStatus())
                .licenseNumber(application.getLicenseNumber())
                .licenseExpiryDate(application.getLicenseExpiryDate())
                .reviewComment(application.getReviewComment())
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                .userSeq(application.getUser().getUserSeq())
                .userFirstName(application.getUser().getFirstName())
                .userLastName(application.getUser().getLastName())
                .userEmail(application.getUser().getEmail())
                .userPhone(application.getUser().getPhone())
                .userCompanyName(application.getUser().getCompanyName())
                .userUen(application.getUser().getUen())
                .userDesignation(application.getUser().getDesignation())
                .userCorrespondenceAddress(application.getUser().getCorrespondenceAddress())
                .userCorrespondencePostalCode(application.getUser().getCorrespondencePostalCode())
                .assignedLewSeq(application.getAssignedLew() != null
                        ? application.getAssignedLew().getUserSeq() : null)
                .assignedLewFirstName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getFirstName() : null)
                .assignedLewLastName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLastName() : null)
                .assignedLewEmail(application.getAssignedLew() != null
                        ? application.getAssignedLew().getEmail() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
                .assignedLewGrade(application.getAssignedLew() != null && application.getAssignedLew().getLewGrade() != null
                        ? application.getAssignedLew().getLewGrade().name() : null)
                .assignedLewMaxKva(application.getAssignedLew() != null && application.getAssignedLew().getLewGrade() != null
                        ? application.getAssignedLew().getLewGrade().getMaxKva() : null)
                // #5: 배정 LEW 등급이 현재 kVA 를 못 다루면 경고 플래그 (파생 — 항상 최신 상태)
                .assignedLewGradeMismatch(application.getAssignedLew() != null
                        && application.getAssignedLew().getLewGrade() != null
                        && application.getSelectedKva() != null
                        && !application.getAssignedLew().canHandleKva(application.getSelectedKva()))
                // SP Account
                .spAccountNo(application.getSpAccountNo())
                // Phase 18 fields
                .applicationType(application.getApplicationType().name())
                .sldFee(application.getSldFee())
                .calloutFee(application.getCalloutFee())
                .originalApplicationSeq(application.getOriginalApplication() != null
                        ? application.getOriginalApplication().getApplicationSeq() : null)
                .existingLicenceNo(application.getExistingLicenceNo())
                .renewalReferenceNo(application.getRenewalReferenceNo())
                .existingExpiryDate(application.getExistingExpiryDate())
                .renewalPeriodMonths(application.getRenewalPeriodMonths())
                .emaFee(application.getEmaFee())
                .sldOption(application.getSldOption() != null ? application.getSldOption().name() : null)
                .loaSignatureUrl(application.getLoaSignatureUrl())
                .loaSignedAt(application.getLoaSignedAt())
                // Phase 5
                .kvaStatus(application.getKvaStatus() != null ? application.getKvaStatus().name() : null)
                .kvaSource(application.getKvaSource() != null ? application.getKvaSource().name() : null)
                .kvaConfirmedBy(application.getKvaConfirmedBy() != null
                        ? application.getKvaConfirmedBy().getUserSeq() : null)
                .kvaConfirmedAt(application.getKvaConfirmedAt())
                // ── EMA 제출 추적 inline ──
                .emaSubmissionStatus(application.getEmaSubmissionStatus() != null
                        ? application.getEmaSubmissionStatus().name() : null)
                .emaSubmittedAt(application.getEmaSubmittedAt())
                .emaReferenceNo(application.getEmaReferenceNo())
                .emaSubmittedByUserSeq(application.getEmaSubmittedByUserSeq())
                .emaDecisionAt(application.getEmaDecisionAt())
                .emaQueryNote(application.getEmaQueryNote())
                .emaGrandfathered(isEmaGrandfathered(application))
                .build();
    }

    /**
     * 허점#2 — backfill grandfathered 식별: APPROVED 인데 결정시각·접수번호가 둘 다 비어있는 건.
     * 정상 승인 건은 둘 중 하나 이상 채워져 있어 false. {@code EmaSubmissionResponse.of} 와 동일 정의.
     */
    private static boolean isEmaGrandfathered(Application application) {
        return application.getEmaSubmissionStatus() == com.bluelight.backend.domain.application.EmaSubmissionStatus.APPROVED
                && application.getEmaDecisionAt() == null
                && (application.getEmaReferenceNo() == null || application.getEmaReferenceNo().isBlank());
    }
}
