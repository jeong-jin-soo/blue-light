package com.bluelight.backend.api.application.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.service.application.ApplicantHintWarning;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Application response DTO
 */
@Getter
@Builder
public class ApplicationResponse {

    private Long applicationSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private BigDecimal quoteAmount;
    private ApplicationStatus status;
    private String licenseNumber;
    private LocalDate licenseExpiryDate;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Phase 19: Assigned LEW info (신청자용 — 이름+면허번호만 노출) ──
    private String assignedLewFirstName;
    private String assignedLewLastName;
    private String assignedLewLicenceNo;

    // SP Group 계정 번호
    private String spAccountNo;

    // Phase 1: 신청자 유형 (INDIVIDUAL | CORPORATE)
    private String applicantType;

    // ── 갱신 + 견적 필드 ──
    private String applicationType;
    private BigDecimal sldFee;
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
    private LocalDateTime kvaConfirmedAt;

    // ── P1.2: EMA ELISE 필드 ──
    private String installationName;
    private String premisesType;
    private Boolean isRentalPremises;
    /** Landlord EI Licence 는 마스킹된 표시값만 노출 (앞 5자 *) — 원본은 LEW 전용 응답에서만 제공 예정. */
    private String landlordEiLicenceMasked;
    private Boolean renewalCompanyNameChanged;
    private Boolean renewalAddressChanged;
    private String installationAddressBlock;
    private String installationAddressUnit;
    private String installationAddressStreet;
    private String installationAddressBuilding;
    private String installationAddressPostalCode;
    private String correspondenceAddressBlock;
    private String correspondenceAddressUnit;
    private String correspondenceAddressStreet;
    private String correspondenceAddressBuilding;
    private String correspondenceAddressPostalCode;

    // ── P1.B: LEW Review Form hint 응답 필드 (스펙 §5.5·§9-17) ──
    // MSSL은 last4만 노출 (평문·enc는 LEW 전용 응답 DTO에서만 제공).
    private String msslHintLast4;
    private Integer supplyVoltageHint;
    private String consumerTypeHint;
    private String retailerHint;
    private Boolean hasGeneratorHint;
    private Integer generatorCapacityHint;

    /** CoF finalize 여부 — 신청자 상세 화면의 "CoF 발급됨" 배지 노출용. */
    private Boolean cofFinalized;
    /** CoF finalize 시각 (신청자에게 공개 가능한 메타 정보). */
    private LocalDateTime cofCertifiedAt;

    /** 경고 수준 검증 결과 (스펙 §5.4·§9-16). 성공 응답에 함께 실리며, 200/201을 절대 깨지 않는다. */
    private List<ApplicantHintWarning> warnings;

    /** Landlord EI Licence 를 본인 입력값 확인 용도로 앞 5자만 마스킹. null/blank 이면 null 반환. */
    private static String maskLandlord(String value) {
        if (value == null || value.isBlank()) return null;
        int n = value.length();
        if (n <= 4) return "*".repeat(n);
        return "*".repeat(Math.max(0, n - 4)) + value.substring(n - 4);
    }

    public static ApplicationResponse from(Application application) {
        return ApplicationResponse.builder()
                .applicationSeq(application.getApplicationSeq())
                .address(application.getAddress())
                .postalCode(application.getPostalCode())
                .buildingType(application.getBuildingType())
                .selectedKva(application.getSelectedKva())
                .quoteAmount(application.getQuoteAmount())
                .status(application.getStatus())
                .licenseNumber(application.getLicenseNumber())
                .licenseExpiryDate(application.getLicenseExpiryDate())
                .reviewComment(application.getReviewComment())
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                // Phase 19: Assigned LEW info
                .assignedLewFirstName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getFirstName() : null)
                .assignedLewLastName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLastName() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
                // SP Account
                .spAccountNo(application.getSpAccountNo())
                // Phase 1: applicantType
                .applicantType(application.getApplicantType() != null
                        ? application.getApplicantType().name() : null)
                // Phase 18 fields
                .applicationType(application.getApplicationType().name())
                .sldFee(application.getSldFee())
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
                .kvaConfirmedAt(application.getKvaConfirmedAt())
                // ── P1.2: EMA ELISE 필드 ──
                .installationName(application.getInstallationName())
                .premisesType(application.getPremisesType() != null ? application.getPremisesType().name() : null)
                .isRentalPremises(application.getIsRentalPremises())
                .landlordEiLicenceMasked(maskLandlord(application.getLandlordEiLicenceNo()))
                .renewalCompanyNameChanged(application.getRenewalCompanyNameChanged())
                .renewalAddressChanged(application.getRenewalAddressChanged())
                .installationAddressBlock(application.getInstallationAddressBlock())
                .installationAddressUnit(application.getInstallationAddressUnit())
                .installationAddressStreet(application.getInstallationAddressStreet())
                .installationAddressBuilding(application.getInstallationAddressBuilding())
                .installationAddressPostalCode(application.getInstallationAddressPostalCode())
                .correspondenceAddressBlock(application.getCorrespondenceAddressBlock())
                .correspondenceAddressUnit(application.getCorrespondenceAddressUnit())
                .correspondenceAddressStreet(application.getCorrespondenceAddressStreet())
                .correspondenceAddressBuilding(application.getCorrespondenceAddressBuilding())
                .correspondenceAddressPostalCode(application.getCorrespondenceAddressPostalCode())
                // ── P1.B: hint + CoF 요약 (warnings는 서비스에서 별도 주입) ──
                .msslHintLast4(application.getApplicantMsslHintLast4())
                .supplyVoltageHint(application.getApplicantSupplyVoltageHint())
                .consumerTypeHint(application.getApplicantConsumerTypeHint())
                .retailerHint(application.getApplicantRetailerHint())
                .hasGeneratorHint(application.getApplicantHasGeneratorHint())
                .generatorCapacityHint(application.getApplicantGeneratorCapacityHint())
                .cofFinalized(application.getCertificateOfFitness() != null
                        && application.getCertificateOfFitness().isFinalized())
                .cofCertifiedAt(application.getCertificateOfFitness() != null
                        ? application.getCertificateOfFitness().getCertifiedAt() : null)
                .build();
    }

    /**
     * warnings를 주입한 복제본 생성 (ApplicationService에서 hint 검증 후 반환 시 사용).
     * from() → withWarnings() 패턴으로 불변성 유지.
     */
    public ApplicationResponse withWarnings(List<ApplicantHintWarning> warnings) {
        return ApplicationResponse.builder()
                .applicationSeq(this.applicationSeq)
                .address(this.address)
                .postalCode(this.postalCode)
                .buildingType(this.buildingType)
                .selectedKva(this.selectedKva)
                .quoteAmount(this.quoteAmount)
                .status(this.status)
                .licenseNumber(this.licenseNumber)
                .licenseExpiryDate(this.licenseExpiryDate)
                .reviewComment(this.reviewComment)
                .createdAt(this.createdAt)
                .updatedAt(this.updatedAt)
                .assignedLewFirstName(this.assignedLewFirstName)
                .assignedLewLastName(this.assignedLewLastName)
                .assignedLewLicenceNo(this.assignedLewLicenceNo)
                .spAccountNo(this.spAccountNo)
                .applicantType(this.applicantType)
                .applicationType(this.applicationType)
                .sldFee(this.sldFee)
                .originalApplicationSeq(this.originalApplicationSeq)
                .existingLicenceNo(this.existingLicenceNo)
                .renewalReferenceNo(this.renewalReferenceNo)
                .existingExpiryDate(this.existingExpiryDate)
                .renewalPeriodMonths(this.renewalPeriodMonths)
                .emaFee(this.emaFee)
                .sldOption(this.sldOption)
                .loaSignatureUrl(this.loaSignatureUrl)
                .loaSignedAt(this.loaSignedAt)
                .kvaStatus(this.kvaStatus)
                .kvaSource(this.kvaSource)
                .kvaConfirmedAt(this.kvaConfirmedAt)
                .installationName(this.installationName)
                .premisesType(this.premisesType)
                .isRentalPremises(this.isRentalPremises)
                .landlordEiLicenceMasked(this.landlordEiLicenceMasked)
                .renewalCompanyNameChanged(this.renewalCompanyNameChanged)
                .renewalAddressChanged(this.renewalAddressChanged)
                .installationAddressBlock(this.installationAddressBlock)
                .installationAddressUnit(this.installationAddressUnit)
                .installationAddressStreet(this.installationAddressStreet)
                .installationAddressBuilding(this.installationAddressBuilding)
                .installationAddressPostalCode(this.installationAddressPostalCode)
                .correspondenceAddressBlock(this.correspondenceAddressBlock)
                .correspondenceAddressUnit(this.correspondenceAddressUnit)
                .correspondenceAddressStreet(this.correspondenceAddressStreet)
                .correspondenceAddressBuilding(this.correspondenceAddressBuilding)
                .correspondenceAddressPostalCode(this.correspondenceAddressPostalCode)
                .msslHintLast4(this.msslHintLast4)
                .supplyVoltageHint(this.supplyVoltageHint)
                .consumerTypeHint(this.consumerTypeHint)
                .retailerHint(this.retailerHint)
                .hasGeneratorHint(this.hasGeneratorHint)
                .generatorCapacityHint(this.generatorCapacityHint)
                .cofFinalized(this.cofFinalized)
                .cofCertifiedAt(this.cofCertifiedAt)
                .warnings(warnings)
                .build();
    }
}
