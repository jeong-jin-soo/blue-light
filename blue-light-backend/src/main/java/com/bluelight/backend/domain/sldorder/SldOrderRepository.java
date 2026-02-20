package com.bluelight.backend.domain.sldorder;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * SLD 전용 주문 Repository
 */
public interface SldOrderRepository extends JpaRepository<SldOrder, Long> {

    /**
     * 신청자 본인 주문 목록 (최신순)
     */
    List<SldOrder> findByUserUserSeqOrderByCreatedAtDesc(Long userSeq);

    /**
     * 전체 주문 목록 (관리용, 최신순, 페이지네이션)
     */
    Page<SldOrder> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 상태별 주문 목록
     */
    Page<SldOrder> findByStatusOrderByCreatedAtDesc(SldOrderStatus status, Pageable pageable);

    /**
     * 담당 매니저별 주문 목록
     */
    Page<SldOrder> findByAssignedManagerUserSeqOrderByCreatedAtDesc(Long managerSeq, Pageable pageable);

    /**
     * 상태별 건수 (대시보드 통계)
     */
    long countByStatus(SldOrderStatus status);
}
