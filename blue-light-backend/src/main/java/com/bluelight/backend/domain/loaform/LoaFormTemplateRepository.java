package com.bluelight.backend.domain.loaform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link LoaFormTemplate} Repository.
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} 덕분에 모든 파생 쿼리는 soft-deleted row 를
 * 자동 제외한다.</p>
 */
@Repository
public interface LoaFormTemplateRepository extends JpaRepository<LoaFormTemplate, Long> {

    /** 전체 버전 목록 — 업로드일 내림차순 (최신 우선). soft-deleted 자동 제외. */
    List<LoaFormTemplate> findAllByOrderByUploadedAtDesc();

    /**
     * 현재 active 인 폼 전부.
     *
     * <p>정상 상태에서는 0 또는 1건이지만, 동시성 사고에 대비해 List 로 받아 서비스에서
     * 일괄 비활성화한다 (active 단일성 복구).</p>
     */
    List<LoaFormTemplate> findByIsActiveTrue();

    /** 현재 active 폼 1건 (소비 API 용). 둘 이상이면 가장 최근 업로드본. */
    Optional<LoaFormTemplate> findFirstByIsActiveTrueOrderByUploadedAtDesc();
}
