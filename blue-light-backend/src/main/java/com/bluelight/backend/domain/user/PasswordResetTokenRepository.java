package com.bluelight.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 Repository
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * 토큰 문자열로 조회
     */
    Optional<PasswordResetToken> findByToken(String token);

    /**
     * 특정 사용자의 최근 토큰 조회 (Rate limiting 용)
     */
    Optional<PasswordResetToken> findTopByUserOrderByCreatedAtDesc(User user);

    /**
     * 만료된 토큰 삭제 (정리 용)
     */
    void deleteByExpiresAtBefore(LocalDateTime dateTime);
}
