package com.bluelight.backend.api.lew.dto;

import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.cof.CertificateOfFitness;
import lombok.Builder;
import lombok.Getter;

/**
 * LEW Review Form — 배정 신청 상세 응답 (lew-review-form-spec.md §3.1).
 *
 * <p>신청자 입력 전체 + Correspondence Address 평문 + Landlord EI Licence 평문 +
 * CoF Draft 현재 값 + 신청자 hint 값 + 입력 여부 플래그를 포함한다. 이 응답은 LEW에게만
 * 반환되며, ADMIN/APPLICANT는 별도 응답 DTO를 사용한다(§3.4, §3.5).</p>
 */
@Getter
@Builder
public class LewApplicationResponse {

    /** Application 기본 필드는 신청자 응답을 재사용 — 단, 이 응답의 소비자는 LEW이므로 MSSL 마스킹 필드는 무시된다. */
    private ApplicationResponse application;

    // ── LEW 전용 평문 노출 필드 (ApplicationResponse에서는 마스킹/은닉되는 값) ──
    /** Landlord EI Licence 평문 (LEW만 열람 가능). */
    private String landlordEiLicenceNo;
    /** Correspondence Address 평문 block (LEW만 열람 가능). */
    private String correspondenceAddressBlockPlain;
    private String correspondenceAddressUnitPlain;
    private String correspondenceAddressStreetPlain;
    private String correspondenceAddressBuildingPlain;

    /** 신청자 hint — MSSL last4 (마스킹 표시용). */
    private String msslHintLast4;
    /** 신청자 hint — MSSL 평문 (LEW Review Form Step 2 prefill용, LEW만 열람). */
    private String msslHintPlain;
    private Integer supplyVoltageHint;
    private String consumerTypeHint;
    private String retailerHint;
    private Boolean hasGeneratorHint;
    private Integer generatorCapacityHint;

    /** "신청자 기입값" 배지용 plain-hint 존재 여부 플래그 (UX 스펙 §6 Step2). */
    private Boolean msslHintProvided;
    private Boolean supplyVoltageHintProvided;
    private Boolean consumerTypeHintProvided;
    private Boolean retailerHintProvided;
    private Boolean generatorHintProvided;

    /** CoF Draft 현재 값. null이면 아직 LEW가 Draft Save를 하지 않은 상태. */
    private CertificateOfFitnessResponse cof;

    /**
     * LEW 조회 응답 변환.
     *
     * @param application         Application 엔티티
     * @param landlordPlain       Landlord EI Licence 평문 (Application의 암호화 컬럼에서 복호화된 값)
     * @param correspondencePlain Correspondence Address 4-part 평문 (block/unit/street/building)
     * @param msslHintPlain       신청자 hint MSSL 평문 (없으면 null)
     * @param cof                 CoF Draft 엔티티 (없으면 null)
     * @param cofMsslPlain        CoF MSSL 평문 (LEW Draft Save 후 세팅된 값, 없으면 null)
     */
    public static LewApplicationResponse from(Application application,
                                               String landlordPlain,
                                               String[] correspondencePlain,
                                               String msslHintPlain,
                                               CertificateOfFitness cof,
                                               String cofMsslPlain) {
        String[] cp = correspondencePlain != null && correspondencePlain.length == 4
                ? correspondencePlain : new String[]{null, null, null, null};

        return LewApplicationResponse.builder()
                .application(ApplicationResponse.from(application))
                .landlordEiLicenceNo(landlordPlain)
                .correspondenceAddressBlockPlain(cp[0])
                .correspondenceAddressUnitPlain(cp[1])
                .correspondenceAddressStreetPlain(cp[2])
                .correspondenceAddressBuildingPlain(cp[3])
                // hint — MSSL은 LEW에게 last4(마스킹) + 평문(prefill용)을 모두 전달. 평문은 EncryptedStringConverter로 복호화된 값.
                .msslHintLast4(application.getApplicantMsslHintLast4())
                .msslHintPlain(msslHintPlain)
                .supplyVoltageHint(application.getApplicantSupplyVoltageHint())
                .consumerTypeHint(application.getApplicantConsumerTypeHint())
                .retailerHint(application.getApplicantRetailerHint())
                .hasGeneratorHint(application.getApplicantHasGeneratorHint())
                .generatorCapacityHint(application.getApplicantGeneratorCapacityHint())
                .msslHintProvided(application.getApplicantMsslHintHmac() != null)
                .supplyVoltageHintProvided(application.getApplicantSupplyVoltageHint() != null)
                .consumerTypeHintProvided(application.getApplicantConsumerTypeHint() != null)
                .retailerHintProvided(application.getApplicantRetailerHint() != null)
                .generatorHintProvided(application.getApplicantHasGeneratorHint() != null)
                .cof(cof != null ? CertificateOfFitnessResponse.from(cof, cofMsslPlain) : null)
                .build();
    }
}
