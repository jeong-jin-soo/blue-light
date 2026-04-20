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
     * 결제 수단 (기본값: CARD)
     */
    @Column(name = "payment_method", length = 20)
    private String paymentMethod = "CARD";

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
                   PaymentReferenceType referenceType, Long referenceSeq) {
        this.application = application;
        this.transactionId = transactionId;
        this.amount = amount;
        this.paymentMethod = paymentMethod != null ? paymentMethod : "CARD";
        this.status = status != null ? status : PaymentStatus.SUCCESS;
        this.paidAt = LocalDateTime.now();

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
