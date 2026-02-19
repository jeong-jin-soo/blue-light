package com.bluelight.backend.domain.ratelimit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface RateLimitAttemptRepository extends JpaRepository<RateLimitAttempt, Long> {

    /**
     * 지정 시간 윈도우 내 시도 횟수 조회
     */
    @Query("SELECT COUNT(r) FROM RateLimitAttempt r " +
           "WHERE r.limiterType = :type AND r.identifier = :identifier " +
           "AND r.attemptedAt > :cutoff")
    long countRecentAttempts(@Param("type") String limiterType,
                             @Param("identifier") String identifier,
                             @Param("cutoff") LocalDateTime cutoff);

    /**
     * 특정 대상의 시도 기록 삭제 (로그인 성공 시 초기화)
     */
    @Modifying
    @Query("DELETE FROM RateLimitAttempt r WHERE r.limiterType = :type AND r.identifier = :identifier")
    void deleteByLimiterTypeAndIdentifier(@Param("type") String limiterType,
                                          @Param("identifier") String identifier);

    /**
     * 만료된 시도 기록 정리 (배치)
     */
    @Modifying
    @Query(value = "DELETE FROM rate_limit_attempts WHERE attempted_at < :cutoff LIMIT :batchSize",
           nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
