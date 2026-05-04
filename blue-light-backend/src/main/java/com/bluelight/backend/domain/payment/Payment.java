package com.bluelight.backend.domain.payment;

import com.bluelight.backend.domain.application.Application;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 로그 Entity
 * - payments 테이블은 created_at 대신 paid_at을 사용하므로 BaseEntity를 상속하지 않음
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE payments SET deleted_at = NOW() WHERE payment_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_seq")
    private Long paymentSeq;

    /**
     * 관련 신청 (FK). ★ PR#7: nullable 전환 — 향후 CONCIERGE_REQUEST 결제는 application=null.
     * 레거시 조회 편의를 위해 필드 자체는 보존한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq")
    private Application application;

    /**
     * 다형 참조 유형 (★ PR#7, PRD §3.8).
     * APPLICATION / CONCIERGE_REQUEST / SLD_ORDER 중 하나.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private PaymentReferenceType referenceType;

    /**
     * 다형 참조 대상 엔티티의 PK (★ PR#7).
     * referenceType에 따라 application.seq / conciergeRequest.seq / sldOrder.seq.
     */
    @Column(name = "reference_seq", nullable = false)
    private Long referenceSeq;

    /**
     * PG사 거래 ID
     */
    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    /**
     * 결제 금액
     */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /**
     * 결제 수단 (기본값: PAYNOW_ONLINE — D2=B).
     * <p>
     * VARCHAR(40)에 {@link PaymentMethod} enum 키를 보관한다. 레거시 호환을 위해 Java 필드는
     * 여전히 {@link String}이지만, 신규 코드는 {@link #createOfflineRecord} 팩토리 또는
     * {@link #getPaymentMethodEnum()}을 사용해 타입 안전성을 확보한다.
     * <p>
     * 백필: 기존 row 의 {@code 'CARD'} 값은 마이그레이션에서 {@code 'PAYNOW_ONLINE'} 으로 변환된다.
     */
    @Column(name = "payment_method", length = 40)
    private String paymentMethod = PaymentMethod.PAYNOW_ONLINE.name();

    /**
     * 결제 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status = PaymentStatus.SUCCESS;

    /**
     * 결제 일시
     */
    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * 수기 결제(offline) 기록자 — ADMIN 사용자의 user_seq.
     * 온라인(PAYNOW_ONLINE) 결제 또는 PR-2 이전 데이터는 NULL.
     * <p>
     * D2=B / 별도 수금 PR-1 인프라 컬럼. 실제 기록은 PR-2 의 별도 수금 엔드포인트에서 수행.
     */
    @Column(name = "recorded_by_user_seq")
    private Long recordedByUserSeq;

    /**
     * 수기 결제(offline) 기록 시점.
     * 온라인 결제는 {@link #paidAt} 만 사용하고, offline 의 경우 ADMIN 이 정산을 기록한 시각을 별도로 보관한다.
     */
    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    /**
     * 수정 일시
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 생성자 ID
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    /**
     * 수정자 ID
     */
    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    /**
     * 삭제 일시 (Soft Delete)
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * ★ PR#7: referenceType/referenceSeq 명시 주입 또는 application으로부터 자동 추론.
     * <p>
     * 호환성 규칙:
     * <ul>
     *   <li>referenceType + referenceSeq 둘 다 주어지면 그대로 사용 (Phase 2 CONCIERGE_REQUEST 결제용)</li>
     *   <li>그 외에 application이 주어지면 {@code APPLICATION} + application.applicationSeq 자동 설정
     *       (기존 호출처 호환)</li>
     *   <li>둘 다 없으면 IllegalArgumentException</li>
     * </ul>
     */
    @Builder
    public Payment(Application application, String transactionId, BigDecimal amount,
                   String paymentMethod, PaymentStatus status,
                   PaymentReferenceType referenceType, Long referenceSeq,
                   Long recordedByUserSeq, LocalDateTime recordedAt) {
        this.application = application;
        this.transactionId = transactionId;
        this.amount = amount;
        // 기본값은 PAYNOW_ONLINE — Concierge PR-1 의 D2=B 결정.
        this.paymentMethod = paymentMethod != null ? paymentMethod : PaymentMethod.PAYNOW_ONLINE.name();
        this.status = status != null ? status : PaymentStatus.SUCCESS;
        this.paidAt = LocalDateTime.now();
        this.recordedByUserSeq = recordedByUserSeq;
        this.recordedAt = recordedAt;

        // ★ PR#7: referenceType/referenceSeq 자동 추론 로직
        if (referenceType != null && referenceSeq != null) {
            this.referenceType = referenceType;
            this.referenceSeq = referenceSeq;
        } else if (application != null && application.getApplicationSeq() != null) {
            this.referenceType = PaymentReferenceType.APPLICATION;
            this.referenceSeq = application.getApplicationSeq();
        } else {
            throw new IllegalArgumentException(
                "Payment requires either (application with seq) or (referenceType + referenceSeq)");
        }
    }

    /**
     * 결제 성공 처리
     */
    public void markAsSuccess(String transactionId) {
        this.transactionId = transactionId;
        this.status = PaymentStatus.SUCCESS;
        this.paidAt = LocalDateTime.now();
    }

    /**
     * 결제 실패 처리
     */
    public void markAsFailed() {
        this.status = PaymentStatus.FAILED;
    }

    /**
     * 환불 처리
     */
    public void refund() {
        this.status = PaymentStatus.REFUNDED;
    }

    /**
     * Soft Delete 수행
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 삭제 여부 확인
     */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    /**
     * ★ PR#7: 이 결제가 특정 (type, seq) 쌍을 참조하는지 확인.
     * 권한 분기(§8.4b)에서 {@code APPLICATION}/{@code CONCIERGE_REQUEST} 소유권 체크에 사용.
     */
    public boolean isLinkedTo(PaymentReferenceType type, Long seq) {
        return this.referenceType == type
            && this.referenceSeq != null
            && this.referenceSeq.equals(seq);
    }

    // ============================================================
    // ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-1 (D2=B)
    // ============================================================

    /**
     * 결제 수단을 enum 으로 반환. 알 수 없는 값(레거시 'CARD' 등)은 {@code null} 을 반환하지 않고
     * {@link PaymentMethod#OTHER} 로 매핑한다 — 호출자가 NPE 가드를 두지 않아도 되도록.
     * <p>
     * 백필 마이그레이션이 'CARD' 를 'PAYNOW_ONLINE' 으로 갱신하므로 정상 데이터에서는 OTHER 가
     * 등장하지 않지만, 마이그레이션 직전 짧은 시점에서도 안전하게 동작한다.
     */
    public PaymentMethod getPaymentMethodEnum() {
        if (this.paymentMethod == null) {
            return PaymentMethod.PAYNOW_ONLINE;
        }
        try {
            return PaymentMethod.valueOf(this.paymentMethod);
        } catch (IllegalArgumentException e) {
            return PaymentMethod.OTHER;
        }
    }

    /**
     * Offline 결제 수단(BANK_TRANSFER / PAYNOW_OFFLINE / CASH / OTHER) 여부.
     * 권한 분기 + 영수증 자동 발행 분기에 사용.
     */
    public boolean isOffline() {
        return getPaymentMethodEnum().isOffline();
    }

    /**
     * ADMIN 의 별도 수금(offline) 기록 팩토리. PR-1 은 시그니처만 도입하고, 실제 호출은 PR-2 의
     * "별도 수금 엔드포인트" 에서 수행한다.
     * <p>
     * 본 팩토리는 Application FK 를 직접 세팅하지 않는다. APPLICATION 결제를 offline 으로 기록할 때
     * 호출자(PR-2 의 별도 수금 엔드포인트)가 application 인자를 별도로 처리하거나, builder 경로를
     * 함께 사용해야 한다. {@code applicantUserSeq} 인자는 결제 알림/영수증 수령자 식별 용도이며
     * 본 PR-1 에서는 Payment 엔티티 자체에 컬럼을 두지 않고 PR-2 에서 service 레이어 인자로 전달된다.
     *
     * @param referenceType     참조 유형 (APPLICATION / CONCIERGE_REQUEST / SLD_ORDER)
     * @param referenceSeq      참조 PK
     * @param amount            결제 금액 (양수)
     * @param method            결제 수단 (offline 4종 중 하나)
     * @param recordedByUserSeq ADMIN 사용자 PK
     * @param recordedAt        기록 시점 (보통 LocalDateTime.now())
     * @param applicantUserSeq  영수증/알림 수령자 — 현재는 Payment 엔티티에 저장하지 않고
     *                          호출자가 후속 단계에서 사용 (PR-2 에서 wiring)
     * @return 미저장 Payment 엔티티 (호출자가 paymentRepository.save() 책임)
     */
    public static Payment createOfflineRecord(
            PaymentReferenceType referenceType,
            Long referenceSeq,
            BigDecimal amount,
            PaymentMethod method,
            Long recordedByUserSeq,
            LocalDateTime recordedAt,
            Long applicantUserSeq) {
        if (referenceType == null || referenceSeq == null) {
            throw new IllegalArgumentException("referenceType and referenceSeq are required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (method == null || !method.isOffline()) {
            throw new IllegalArgumentException("createOfflineRecord requires an offline PaymentMethod");
        }
        if (recordedByUserSeq == null) {
            throw new IllegalArgumentException("recordedByUserSeq is required");
        }
        // applicantUserSeq 는 시그니처 통과용 — PR-1 에서는 검증만 하고 엔티티에 저장하지 않는다.
        // null 도 허용 (CONCIERGE_REQUEST 결제는 ConciergeRequest.applicantUser 에서 도출 가능).
        Payment p = Payment.builder()
                .amount(amount)
                .paymentMethod(method.name())
                .status(PaymentStatus.SUCCESS)
                .referenceType(referenceType)
                .referenceSeq(referenceSeq)
                .recordedByUserSeq(recordedByUserSeq)
                .recordedAt(recordedAt != null ? recordedAt : LocalDateTime.now())
                .build();
        return p;
    }

    @PrePersist
    protected void onPrePersist() {
        this.paidAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
