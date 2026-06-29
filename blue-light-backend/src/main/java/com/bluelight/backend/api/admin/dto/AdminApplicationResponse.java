package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.LicenseStatus;
import com.bluelight.backend.domain.user.User;
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
        // 신청이 소프트삭제(@SQLRestriction deleted_at IS NULL)되거나 물리삭제된 사용자를 참조하면
        // 프록시 접근(getUserSeq 포함 — ID getter 도 초기화를 유발)이 EntityNotFoundException 으로 터진다.
        // resolveOrNull 안에서만 프록시를 만지고(catch), 그 밖에서는 raw 프록시를 절대 건드리지 않는다.
        // 삭제된 사용자는 seq·이름 모두 null 로 렌더해 목록 전체가 깨지지 않게 한다.
        User applicant = resolveOrNull(application.getUser());
        Long applicantSeq = applicant != null ? applicant.getUserSeq() : null;
        User lew = resolveOrNull(application.getAssignedLew());
        Long lewSeq = lew != null ? lew.getUserSeq() : null;
        User kvaConfirmedByUser = resolveOrNull(application.getKvaConfirmedBy());
        Application originalApp = resolveOrNull(application.getOriginalApplication());
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
                .userSeq(applicantSeq)
                .userFirstName(applicant != null ? applicant.getFirstName() : null)
                .userLastName(applicant != null ? applicant.getLastName() : null)
                .userEmail(applicant != null ? applicant.getEmail() : null)
                .userPhone(applicant != null ? applicant.getPhone() : null)
                .userCompanyName(applicant != null ? applicant.getCompanyName() : null)
                .userUen(applicant != null ? applicant.getUen() : null)
                .userDesignation(applicant != null ? applicant.getDesignation() : null)
                .userCorrespondenceAddress(applicant != null ? applicant.getCorrespondenceAddress() : null)
                .userCorrespondencePostalCode(applicant != null ? applicant.getCorrespondencePostalCode() : null)
                .assignedLewSeq(lewSeq)
                .assignedLewFirstName(lew != null ? lew.getFirstName() : null)
                .assignedLewLastName(lew != null ? lew.getLastName() : null)
                .assignedLewEmail(lew != null ? lew.getEmail() : null)
                .assignedLewLicenceNo(lew != null ? lew.getLewLicenceNo() : null)
                .assignedLewGrade(lew != null && lew.getLewGrade() != null
                        ? lew.getLewGrade().name() : null)
                .assignedLewMaxKva(lew != null && lew.getLewGrade() != null
                        ? lew.getLewGrade().getMaxKva() : null)
                // #5: 배정 LEW 등급이 현재 kVA 를 못 다루면 경고 플래그 (파생 — 항상 최신 상태)
                .assignedLewGradeMismatch(lew != null && lew.getLewGrade() != null
                        && application.getSelectedKva() != null
                        && !lew.canHandleKva(application.getSelectedKva()))
                // SP Account
                .spAccountNo(application.getSpAccountNo())
                // Phase 18 fields
                .applicationType(application.getApplicationType().name())
                .sldFee(application.getSldFee())
                .calloutFee(application.getCalloutFee())
                .originalApplicationSeq(originalApp != null ? originalApp.getApplicationSeq() : null)
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
                .kvaConfirmedBy(kvaConfirmedByUser != null ? kvaConfirmedByUser.getUserSeq() : null)
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
    /**
     * 프록시(User/Application 등)를 초기화하되, 참조 행이 없으면(소프트삭제 @SQLRestriction/물리삭제)
     * null 을 돌려준다. 삭제된 엔티티를 참조하는 단 한 건이 목록 전체를 500 으로 깨뜨리는 것을 막는다.
     * <p>주의: 초기화 후에는 resolve 된 객체로만 필드(ID 포함)에 접근할 것 — raw 프록시의 getId 도
     * 미초기화 시 EntityNotFoundException 을 던진다.</p>
     */
    private static <T> T resolveOrNull(T proxy) {
        if (proxy == null) {
            return null;
        }
        try {
            org.hibernate.Hibernate.initialize(proxy);
            return proxy;
        } catch (jakarta.persistence.EntityNotFoundException | org.hibernate.ObjectNotFoundException e) {
            return null;
        }
    }

    private static boolean isEmaGrandfathered(Application application) {
        return application.getEmaSubmissionStatus() == com.bluelight.backend.domain.application.EmaSubmissionStatus.APPROVED
                && application.getEmaDecisionAt() == null
                && (application.getEmaReferenceNo() == null || application.getEmaReferenceNo().isBlank());
    }
}
