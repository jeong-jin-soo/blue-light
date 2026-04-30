package com.bluelight.backend.domain.kva;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 결제 후 kVA 사후 변경 ledger Repository.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.1.</p>
 */
@Repository
public interface KvaAdjustmentRepository extends JpaRepository<KvaAdjustmentRecord, Long> {

    /**
     * 특정 신청의 변경 이력을 작성순(adjustmentSeq ASC) 으로 조회.
     */
    List<KvaAdjustmentRecord> findByApplication_ApplicationSeqOrderByAdjustmentSeqAsc(Long applicationSeq);
}
