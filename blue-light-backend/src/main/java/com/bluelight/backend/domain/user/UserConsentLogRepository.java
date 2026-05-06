package com.bluelight.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * UserConsentLog Repository (★ Kaki Concierge v1.3)
 * <p>
 * PDPA 증적 조회 + 철회 이력 추적용. 수정/삭제 API는 제공하지 않는다
 * (엔티티 필드가 모두 {@code updatable=false}이며 soft delete 미적용).
 */
@Repository
public interface UserConsentLogRepository extends JpaRepository<UserConsentLog, Long> {

    /**
     * 특정 사용자의 전체 동의 이력 (최신순)
     */
    List<UserConsentLog> findAllByUser_UserSeqOrderByCreatedAtDesc(Long userSeq);

    /**
     * 특정 사용자의 특정 동의 타입 이력 (GRANTED/WITHDRAWN 시계열)
     */
    List<UserConsentLog> findAllByUser_UserSeqAndConsentType(Long userSeq, ConsentType consentType);
}
