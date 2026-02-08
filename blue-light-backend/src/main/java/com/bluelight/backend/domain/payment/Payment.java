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
     * 관련 신청 (FK)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

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

    @Builder
    public Payment(Application application, String transactionId, BigDecimal amount,
                   String paymentMethod, PaymentStatus status) {
        this.application = application;
        this.transactionId = transactionId;
        this.amount = amount;
        this.paymentMethod = paymentMethod != null ? paymentMethod : "CARD";
        this.status = status != null ? status : PaymentStatus.SUCCESS;
        this.paidAt = LocalDateTime.now();
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
