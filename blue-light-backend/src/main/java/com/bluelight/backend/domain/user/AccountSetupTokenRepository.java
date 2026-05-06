package com.bluelight.backend.domain.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * AccountSetupToken Repository (★ Kaki Concierge v1.5)
 */
@Repository
public interface AccountSetupTokenRepository extends JpaRepository<AccountSetupToken, Long> {

    /**
     * URL path의 UUID로 토큰 조회
     */
    Optional<AccountSetupToken> findByTokenUuid(String tokenUuid);

    /**
     * O-17: 특정 유저의 활성(사용 가능) 토큰 목록 조회
     * - 서비스 레이어가 신규 발급 전에 기존 토큰을 revoke() 처리하기 위해 사용
     * - soft delete 필터는 @SQLRestriction이 자동 적용
     */
    @Query("SELECT t FROM AccountSetupToken t " +
           "WHERE t.user.userSeq = :userSeq " +
           "  AND t.usedAt IS NULL " +
           "  AND t.revokedAt IS NULL " +
           "  AND t.lockedAt IS NULL " +
           "  AND t.expiresAt > CURRENT_TIMESTAMP")
    List<AccountSetupToken> findActiveTokensByUser(@Param("userSeq") Long userSeq);
}
