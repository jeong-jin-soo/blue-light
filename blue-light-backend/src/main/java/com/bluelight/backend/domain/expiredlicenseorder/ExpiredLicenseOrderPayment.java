package com.bluelight.backend.domain.expiredlicenseorder;

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

@Entity
@Table(name = "expired_license_order_payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@SQLDelete(sql = "UPDATE expired_license_order_payments SET deleted_at = NOW() WHERE expired_license_order_payment_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ExpiredLicenseOrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "expired_license_order_payment_seq")
    private Long expiredLicenseOrderPaymentSeq;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expired_license_order_seq", nullable = false)
    private ExpiredLicenseOrder expiredLicenseOrder;

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
    public ExpiredLicenseOrderPayment(ExpiredLicenseOrder expiredLicenseOrder, BigDecimal amount,
                                      String paymentMethod, String transactionId) {
        this.expiredLicenseOrder = expiredLicenseOrder;
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
