package com.bluelight.backend.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Payment Entity Repository
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * 특정 신청의 결제 내역 조회
     */
    List<Payment> findByApplicationApplicationSeq(Long applicationSeq);

    /**
     * 특정 신청의 성공한 결제 조회
     */
    Optional<Payment> findByApplicationApplicationSeqAndStatus(Long applicationSeq, PaymentStatus status);

    /**
     * PG사 거래 ID로 결제 조회
     */
    Optional<Payment> findByTransactionId(String transactionId);
}
