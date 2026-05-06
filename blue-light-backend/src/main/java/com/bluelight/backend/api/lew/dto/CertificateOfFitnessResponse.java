package com.bluelight.backend.api.lew.dto;

import com.bluelight.backend.domain.cof.CertificateOfFitness;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * LEW Review Form — CoF 응답 DTO (lew-review-form-spec.md §3.2·§3.3).
 *
 * <p>LEW에게는 MSSL 평문이 복호화되어 노출된다. 이 DTO는 오직 LEW 컨트롤러에서만 사용하며,
 * ADMIN/APPLICANT에게 반환되어서는 안 된다 (둘은 마스킹된 last4 + 공개 필드만 노출).</p>
 */
@Getter
@Builder
public class CertificateOfFitnessResponse {

    private Long cofSeq;
    private Long applicationSeq;

    /** MSSL Account No 평문 (LEW 전용). null이면 신청자가 hint 미제공 + LEW도 미입력. */
    private String msslAccountNo;
    /** MSSL 뒤 4자리 (표시용 보조 정보). */
    private String msslAccountNoLast4;

    private ConsumerType consumerType;
    private RetailerCode retailerCode;
    private Integer supplyVoltageV;
    private Integer approvedLoadKva;
    private Boolean hasGenerator;
    private Integer generatorCapacityKva;
    private Integer inspectionIntervalMonths;

    private LocalDate lewAppointmentDate;
    private LocalDate lewConsentDate;

    /** 확정한 LEW userSeq (null이면 Draft). */
    private Long certifiedByLewSeq;
    /** finalize 시각 (null이면 Draft). */
    private LocalDateTime certifiedAt;
    private LocalDateTime draftSavedAt;

    /** 낙관적 락 버전 — 클라이언트가 보관 후 재제출 시 충돌 검출에 사용 가능. */
    private Integer version;

    /** 편의 필드: finalize 여부. */
    private Boolean finalized;

    /**
     * 엔티티 → Response 변환. MSSL 평문은 컨버터가 이미 복호화한 값.
     */
    public static CertificateOfFitnessResponse from(CertificateOfFitness cof, String msslPlain) {
        return CertificateOfFitnessResponse.builder()
                .cofSeq(cof.getCofSeq())
                .applicationSeq(cof.getApplication().getApplicationSeq())
                .msslAccountNo(msslPlain)
                .msslAccountNoLast4(cof.getMsslAccountNoLast4())
                .consumerType(cof.getConsumerType())
                .retailerCode(cof.getRetailerCode())
                .supplyVoltageV(cof.getSupplyVoltageV())
                .approvedLoadKva(cof.getApprovedLoadKva())
                .hasGenerator(cof.getHasGenerator())
                .generatorCapacityKva(cof.getGeneratorCapacityKva())
                .inspectionIntervalMonths(cof.getInspectionIntervalMonths())
                .lewAppointmentDate(cof.getLewAppointmentDate())
                .lewConsentDate(cof.getLewConsentDate())
                .certifiedByLewSeq(cof.getCertifiedByLew() != null
                        ? cof.getCertifiedByLew().getUserSeq() : null)
                .certifiedAt(cof.getCertifiedAt())
                .draftSavedAt(cof.getDraftSavedAt())
                .version(cof.getVersion())
                .finalized(cof.isFinalized())
                .build();
    }
}
