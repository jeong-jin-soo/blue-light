package com.bluelight.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Entity Repository
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 이메일로 사용자 조회
     */
    Optional<User> findByEmail(String email);

    /**
     * 이메일 존재 여부 확인
     */
    boolean existsByEmail(String email);

    /**
     * 역할 + 승인 상태로 사용자 목록 조회 (예: 승인된 LEW 목록)
     */
    List<User> findByRoleAndApprovedStatus(UserRole role, ApprovalStatus approvedStatus);
}
