package com.bluelight.backend.domain.kva;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 특정 신청의 특정 status row 를 비관적 락(PESSIMISTIC_WRITE)으로 조회.
     *
     * <p>PR-3 / 스펙 §10 D4: LEW 의 중복 PENDING 요청을 차단하기 위해
     * {@code KvaPostPaymentService.requestAdjustmentByLew} 가 트랜잭션 내에서 호출.
     * MySQL 8 InnoDB 에서 {@code SELECT ... FOR UPDATE} 로 매핑되어 같은 application 의
     * 동시 요청이 직렬화된다.</p>
     *
     * <p>또한 AC-L4 (ADMIN 직접 override 시 PENDING LEW 요청 자동 RESOLVED 마킹) 처리에서도
     * {@code KvaPostPaymentService.overrideKva} 가 동일 메서드로 PENDING row 를 락 후 갱신한다.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM KvaAdjustmentRecord r "
            + "WHERE r.application.applicationSeq = :applicationSeq AND r.status = :status "
            + "ORDER BY r.adjustmentSeq ASC")
    List<KvaAdjustmentRecord> findByApplicationSeqAndStatusForUpdate(
            @Param("applicationSeq") Long applicationSeq,
            @Param("status") KvaAdjustmentStatus status);
}
