package com.bluelight.backend.domain.concierge;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ConciergeRequest Repository (★ Kaki Concierge v1.5)
 */
@Repository
public interface ConciergeRequestRepository extends JpaRepository<ConciergeRequest, Long> {

    /**
     * publicCode(C-YYYY-NNNN)로 조회
     */
    Optional<ConciergeRequest> findByPublicCode(String publicCode);

    /**
     * publicCode 중복 체크 (코드 생성 시 충돌 방지)
     */
    boolean existsByPublicCode(String publicCode);

    /**
     * 상태별 목록 (Admin 대시보드 필터)
     */
    List<ConciergeRequest> findAllByStatus(ConciergeRequestStatus status);

    /**
     * Manager가 담당하는 요청 목록 (Manager 대시보드)
     */
    List<ConciergeRequest> findAllByAssignedManager_UserSeq(Long managerSeq);

    /**
     * 이메일 기준 재신청/중복 조회
     */
    List<ConciergeRequest> findAllBySubmitterEmail(String submitterEmail);

    // ────────────────────────────────────────────────────────────
    // ★ Phase 1 PR#4 Stage A — Manager 대시보드용 검색/필터
    // ────────────────────────────────────────────────────────────

    /**
     * 대시보드 검색. managerSeq=null이면 전체(Admin), 아니면 해당 매니저 배정 건만.
     * status/q는 선택. q는 submitterName/submitterEmail/publicCode 부분일치(대소문자 무시).
     */
    @Query("SELECT c FROM ConciergeRequest c " +
           "WHERE (:managerSeq IS NULL OR c.assignedManager.userSeq = :managerSeq) " +
           "AND (:status IS NULL OR c.status = :status) " +
           "AND (:q IS NULL OR " +
           "     LOWER(c.submitterName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "     LOWER(c.submitterEmail) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "     LOWER(c.publicCode) LIKE LOWER(CONCAT('%', :q, '%')))")
    Page<ConciergeRequest> searchForDashboard(
        @Param("managerSeq") Long managerSeq,
        @Param("status") ConciergeRequestStatus status,
        @Param("q") String q,
        Pageable pageable);

    /**
     * KPI 카운트 — 매니저별 상태 집계
     */
    long countByAssignedManager_UserSeqAndStatus(Long managerSeq, ConciergeRequestStatus status);

    /**
     * KPI 카운트 — 전체 상태 집계 (Admin)
     */
    long countByStatus(ConciergeRequestStatus status);
}
