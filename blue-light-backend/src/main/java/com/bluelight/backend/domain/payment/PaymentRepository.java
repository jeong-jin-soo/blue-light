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

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 — 다형 참조 결제 조회.
     * <p>
     * (referenceType, referenceSeq) 조합으로 결제 내역 조회. PR-2 의 별도 수금 엔드포인트가
     * 중복 기록 검사 + 영수증 발행 시 같은 reference 의 ACTIVE 결제 존재 여부를 빠르게 확인하기 위해 사용.
     * 정렬 없음 — 호출자가 paid_at DESC 등으로 후처리.
     */
    List<Payment> findByReferenceTypeAndReferenceSeq(PaymentReferenceType referenceType, Long referenceSeq);
}
