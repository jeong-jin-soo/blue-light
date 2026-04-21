package com.bluelight.backend.domain.lewserviceorder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Request for LEW Service 주문 Repository
 */
public interface LewServiceOrderRepository extends JpaRepository<LewServiceOrder, Long> {

    /**
     * 신청자 본인 주문 목록 (최신순)
     */
    List<LewServiceOrder> findByUserUserSeqOrderByCreatedAtDesc(Long userSeq);

    /**
     * 전체 주문 목록 (관리용, 최신순, 페이지네이션)
     */
    Page<LewServiceOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 상태별 주문 목록
     */
    Page<LewServiceOrder> findByStatusOrderByCreatedAtDesc(LewServiceOrderStatus status, Pageable pageable);

    /**
     * 담당 매니저별 주문 목록
     */
    Page<LewServiceOrder> findByAssignedManagerUserSeqOrderByCreatedAtDesc(Long managerSeq, Pageable pageable);

    /**
     * 상태별 건수 (대시보드 통계)
     */
    long countByStatus(LewServiceOrderStatus status);
}
