package com.bluelight.backend.domain.lewserviceorder;

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
 * Request for LEW Service 주문 결제 Entity
 * - 기존 Payment 엔티티가 Application FK를 필수로 가지므로 별도 생성
 */
@Entity
@Table(name = "lew_service_order_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE lew_service_order_payments SET deleted_at = NOW() WHERE lew_service_order_payment_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class LewServiceOrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lew_service_order_payment_seq")
    private Long lewServiceOrderPaymentSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lew_service_order_seq", nullable = false)
    private LewServiceOrder lewServiceOrder;

    @Column(name = "transaction_id", length = 100)
    private String transactionId;

    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", length = 20)
    private String paymentMethod = "BANK_TRANSFER";

    @Column(name = "status", nullable = false, length = 20)
    private String status = "SUCCESS";

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public LewServiceOrderPayment(LewServiceOrder lewServiceOrder, BigDecimal amount, String paymentMethod, String transactionId) {
        this.lewServiceOrder = lewServiceOrder;
        this.amount = amount;
        this.paymentMethod = paymentMethod != null ? paymentMethod : "BANK_TRANSFER";
        this.transactionId = transactionId;
        this.status = "SUCCESS";
        this.paidAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onPrePersist() {
        this.paidAt = this.paidAt != null ? this.paidAt : LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
