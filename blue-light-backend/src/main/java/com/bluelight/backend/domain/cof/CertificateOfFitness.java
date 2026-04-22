package com.bluelight.backend.domain.cof;

import com.bluelight.backend.common.crypto.EncryptedStringConverter;
import com.bluelight.backend.common.crypto.HmacStringConverter;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Certificate of Fitness — LEW가 EMA ELISE 제출 전에 확정하는 CoF 필드 묶음.
 *
 * <p>{@link Application}과 1:1 매핑. 신청 당시에는 미생성(null), LEW의 Draft Save
 * 시점에 insert된다. {@code certified_at IS NULL}이면 Draft, 값이 있으면 finalized.</p>
 *
 * <h3>보안/PDPA</h3>
 * <ul>
 *   <li>MSSL Account No: AES-256-GCM 암호문(앞 12자리) + HMAC-SHA256 검색 해시 + 뒤 4자리 평문.
 *       ema-pdpa-assessment.md §9(①) 3중 저장 패턴.</li>
 *   <li>접근 제어: LEW(배정된 1인)만 R/W, ADMIN/SYSTEM_ADMIN은 R(마스킹),
 *       APPLICANT는 R(제출 후, 마스킹 + 일부 필드 은닉). API 레이어에서 강제.</li>
 * </ul>
 *
 * <h3>동시성</h3>
 * {@code @Version} 낙관적 락 — 동일 신청을 다른 기기에서 동시 편집 시 충돌을 409로 변환
 * (클라이언트 재로드 유도).
 *
 * <h3>도메인 규칙</h3>
 * <ul>
 *   <li>{@link #saveDraft()} — draftSavedAt 갱신 (컨트롤러가 persist/merge 직전 호출).</li>
 *   <li>{@link #finalize(User, LocalDate)} — certified_by/at/consent_date 기록. 이미 확정된
 *       레코드에 대해서는 {@link IllegalStateException} (Acceptance Criteria §9-5).</li>
 *   <li>{@link #isFinalized()} — certified_at 기반 판정.</li>
 * </ul>
 *
 * <p>필드 validation 어노테이션(@NotNull 등)은 엔티티에 직접 걸지 않고 DTO 레벨에서 수행한다.</p>
 */
@Entity
@Table(name = "certificate_of_fitness")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "cofSeq", callSuper = false)
@SQLDelete(sql = "UPDATE certificate_of_fitness SET deleted_at = NOW() WHERE cof_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class CertificateOfFitness extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cof_seq")
    private Long cofSeq;

    /**
     * 매핑된 Application (1:1, UNIQUE FK).
     * <p>fetch=LAZY — 조회 경로에서 불필요한 join 억제.</p>
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_seq", nullable = false, unique = true)
    private Application application;

    // ── MSSL Account No (3중 저장) ──

    /**
     * MSSL Account No 앞 12자리 (xxx-xx-xxxx-x 중 최종 체크디지트 직전까지) 암호문.
     * AES-256-GCM, `v1:BASE64(...)` 포맷.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mssl_account_no_enc", length = 255)
    private String msslAccountNoEnc;

    /**
     * MSSL Account No 전체의 HMAC-SHA256 검색 해시 (64자 hex).
     * <p>중복 검증·검색에 사용. 평문은 저장하지 않는다.</p>
     */
    @Convert(converter = HmacStringConverter.class)
    @Column(name = "mssl_account_no_hmac", length = 64)
    private String msslAccountNoHmac;

    /**
     * MSSL Account No 뒤 4자리 평문 — 마스킹 UI 표시용 ({@code ***-**-****-NNNN}).
     */
    @Column(name = "mssl_account_no_last4", length = 4)
    private String msslAccountNoLast4;

    // ── Consumer / Retailer ──

    @Enumerated(EnumType.STRING)
    @Column(name = "consumer_type", nullable = false, length = 20)
    private ConsumerType consumerType = ConsumerType.NON_CONTESTABLE;

    /**
     * 리테일러 코드. Non-contestable은 {@link RetailerCode#SP_SERVICES_LIMITED} 고정,
     * Contestable은 자유 선택 (마스터: {@link RetailerCode}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "retailer_code", length = 32)
    private RetailerCode retailerCode = RetailerCode.SP_SERVICES_LIMITED;

    // ── 전기 제원 ──

    /** 공급 전압 (V): 230/400/6600/22000 — DB CHECK 제약으로 강제. */
    @Column(name = "supply_voltage_v", nullable = false)
    private Integer supplyVoltageV;

    /** 승인 부하 (kVA). 신청자 selectedKva prefill, LEW가 덮어쓸 수 있다. */
    @Column(name = "approved_load_kva", nullable = false)
    private Integer approvedLoadKva;

    /** 발전기 보유 여부. */
    @Column(name = "has_generator", nullable = false)
    private Boolean hasGenerator = false;

    /** 발전기 용량 (kVA). {@code hasGenerator=true}일 때 서비스가 필수 검증. */
    @Column(name = "generator_capacity_kva")
    private Integer generatorCapacityKva;

    // ── 점검/서명 ──

    /** 점검 주기 (개월): 6/12/24/36/60 — DB CHECK 제약으로 강제. */
    @Column(name = "inspection_interval_months", nullable = false)
    private Integer inspectionIntervalMonths;

    /** LEW 선임일 (기본값 오늘, 편집 가능). */
    @Column(name = "lew_appointment_date", nullable = false)
    private LocalDate lewAppointmentDate;

    /** LEW 동의일 (finalize 시 자동 today, 수기 과거 일자 기입 허용). */
    @Column(name = "lew_consent_date")
    private LocalDate lewConsentDate;

    /** finalize 시 기록되는 인증 LEW. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "certified_by_lew_seq")
    private User certifiedByLew;

    /** finalize 시각. null이면 Draft. */
    @Column(name = "certified_at")
    private LocalDateTime certifiedAt;

    /** 마지막 Draft Save 시각. */
    @Column(name = "draft_saved_at")
    private LocalDateTime draftSavedAt;

    /** 낙관적 락 버전. */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @Builder
    public CertificateOfFitness(Application application,
                                String msslAccountNoEnc,
                                String msslAccountNoHmac,
                                String msslAccountNoLast4,
                                ConsumerType consumerType,
                                RetailerCode retailerCode,
                                Integer supplyVoltageV,
                                Integer approvedLoadKva,
                                Boolean hasGenerator,
                                Integer generatorCapacityKva,
                                Integer inspectionIntervalMonths,
                                LocalDate lewAppointmentDate,
                                LocalDate lewConsentDate,
                                User certifiedByLew,
                                LocalDateTime certifiedAt,
                                LocalDateTime draftSavedAt) {
        this.application = application;
        this.msslAccountNoEnc = msslAccountNoEnc;
        this.msslAccountNoHmac = msslAccountNoHmac;
        this.msslAccountNoLast4 = msslAccountNoLast4;
        this.consumerType = consumerType != null ? consumerType : ConsumerType.NON_CONTESTABLE;
        this.retailerCode = retailerCode != null ? retailerCode : RetailerCode.SP_SERVICES_LIMITED;
        this.supplyVoltageV = supplyVoltageV;
        this.approvedLoadKva = approvedLoadKva;
        this.hasGenerator = hasGenerator != null ? hasGenerator : Boolean.FALSE;
        this.generatorCapacityKva = generatorCapacityKva;
        this.inspectionIntervalMonths = inspectionIntervalMonths;
        this.lewAppointmentDate = lewAppointmentDate != null ? lewAppointmentDate : LocalDate.now();
        this.lewConsentDate = lewConsentDate;
        this.certifiedByLew = certifiedByLew;
        this.certifiedAt = certifiedAt;
        this.draftSavedAt = draftSavedAt;
    }

    // ── 도메인 메서드 ──

    /**
     * Draft Save 시각 갱신. 컨트롤러/서비스가 persist/merge 직전에 호출.
     *
     * @throws IllegalStateException 이미 finalize된 레코드 (draft 갱신 금지)
     */
    public void saveDraft() {
        if (isFinalized()) {
            throw new IllegalStateException("Cannot save draft on a finalized CoF");
        }
        this.draftSavedAt = LocalDateTime.now();
    }

    /**
     * CoF 확정. 아래 부수효과 3종을 한번에 기록:
     * <ul>
     *   <li>{@code certifiedByLew = lewUser}</li>
     *   <li>{@code certifiedAt = now()}</li>
     *   <li>{@code lewConsentDate = consentDate ?? today}</li>
     * </ul>
     *
     * <p>이미 확정된 레코드 재호출 시 {@link IllegalStateException}
     * (Acceptance Criteria §9-5 — 컨트롤러/서비스는 409로 변환).</p>
     *
     * @param lewUser     인증 LEW (필수)
     * @param consentDate 동의일 (null이면 today로 대체)
     */
    public void finalize(User lewUser, LocalDate consentDate) {
        if (lewUser == null) {
            throw new IllegalArgumentException("LEW user must not be null for finalize");
        }
        if (isFinalized()) {
            throw new IllegalStateException("CoF is already finalized");
        }
        this.certifiedByLew = lewUser;
        this.certifiedAt = LocalDateTime.now();
        this.lewConsentDate = consentDate != null ? consentDate : LocalDate.now();
    }

    /** finalize 여부. */
    public boolean isFinalized() {
        return this.certifiedAt != null;
    }

    // ── 내부 갱신 헬퍼 (서비스/컨트롤러에서 DTO → 엔티티 반영 시 사용) ──

    /**
     * MSSL 3종 필드를 일괄 갱신. 평문은 서비스 레이어에서 암호화/해시/4자리 추출 후 전달.
     * Draft 단계에서만 호출 — finalized 후에는 MSSL 변경 불가.
     */
    public void updateMssl(String enc, String hmac, String last4) {
        if (isFinalized()) {
            throw new IllegalStateException("Cannot update MSSL on a finalized CoF");
        }
        this.msslAccountNoEnc = enc;
        this.msslAccountNoHmac = hmac;
        this.msslAccountNoLast4 = last4;
    }

    /**
     * CoF 10 필드(Draft Save) 일괄 갱신. MSSL은 별도 {@link #updateMssl(String, String, String)}로 처리.
     */
    public void updateFields(ConsumerType consumerType,
                             RetailerCode retailerCode,
                             Integer supplyVoltageV,
                             Integer approvedLoadKva,
                             Boolean hasGenerator,
                             Integer generatorCapacityKva,
                             Integer inspectionIntervalMonths,
                             LocalDate lewAppointmentDate,
                             LocalDate lewConsentDate) {
        if (isFinalized()) {
            throw new IllegalStateException("Cannot update fields on a finalized CoF");
        }
        if (consumerType != null) this.consumerType = consumerType;
        if (retailerCode != null) this.retailerCode = retailerCode;
        if (supplyVoltageV != null) this.supplyVoltageV = supplyVoltageV;
        if (approvedLoadKva != null) this.approvedLoadKva = approvedLoadKva;
        if (hasGenerator != null) this.hasGenerator = hasGenerator;
        // hasGenerator=false로 전환될 때 capacity는 정리
        if (Boolean.FALSE.equals(this.hasGenerator)) {
            this.generatorCapacityKva = null;
        } else if (generatorCapacityKva != null) {
            this.generatorCapacityKva = generatorCapacityKva;
        }
        if (inspectionIntervalMonths != null) this.inspectionIntervalMonths = inspectionIntervalMonths;
        if (lewAppointmentDate != null) this.lewAppointmentDate = lewAppointmentDate;
        if (lewConsentDate != null) this.lewConsentDate = lewConsentDate;
    }
}
