package com.bluelight.backend.domain.concierge;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
