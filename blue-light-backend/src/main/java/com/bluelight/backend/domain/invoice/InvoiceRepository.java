package com.bluelight.backend.domain.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * E-Invoice Repository.
 * Soft delete는 엔티티의 {@code @SQLRestriction("deleted_at IS NULL")} 로 자동 필터.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** Payment 당 1:1 조회 — unique constraint 기반. */
    Optional<Invoice> findByPaymentSeq(Long paymentSeq);

    /** 중복 발행 방지용 선검사. */
    boolean existsByPaymentSeq(Long paymentSeq);

    /** Application 경유 조회 (reference_type=APPLICATION 인 경우). */
    Optional<Invoice> findByApplicationSeqAndReferenceType(Long applicationSeq, String referenceType);

    /**
     * 영수증 번호 접두(예: "IN20260422") 기준 카운트.
     * InvoiceNumberGenerator(P1.b)에서 일별 seq 채번에 사용.
     */
    long countByInvoiceNumberStartingWith(String prefix);

    /**
     * 발번 후보 번호 중복 여부 검사 (Generator 재시도 루프용).
     * soft-deleted 영수증은 @SQLRestriction으로 자동 제외되므로,
     * 활성 영수증끼리만 UNIQUE 제약을 검사한다.
     */
    boolean existsByInvoiceNumber(String invoiceNumber);
}
