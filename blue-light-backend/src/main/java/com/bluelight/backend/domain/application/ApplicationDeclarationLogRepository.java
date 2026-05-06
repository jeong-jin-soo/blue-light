package com.bluelight.backend.domain.application;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@link ApplicationDeclarationLog} JPA 리포지토리.
 *
 * 감사용 append-only 이므로 update/delete 계열 메서드는 추가하지 않는다.
 */
public interface ApplicationDeclarationLogRepository
        extends JpaRepository<ApplicationDeclarationLog, Long> {

    /** 특정 신청의 모든 동의 로그 조회 (applicationSeq 기준). */
    List<ApplicationDeclarationLog> findByApplicationApplicationSeq(Long applicationSeq);

    /**
     * Query-by-column 편의 메서드 — `application_seq` 컬럼 값을 직접 받는 호출부 호환.
     * `application.applicationSeq` 경로를 그대로 위임한다.
     */
    default List<ApplicationDeclarationLog> findByApplicationSeq(Long applicationSeq) {
        return findByApplicationApplicationSeq(applicationSeq);
    }
}
