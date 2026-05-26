package com.bluelight.backend.domain.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 알림 템플릿 카탈로그 Repository.
 *
 * <p>Orchestrator 는 (template_code, channel, locale) 키로 본 Repository 를 조회한다.
 * locale 미스 시 영어(en) 폴백은 호출 측에서 처리.</p>
 *
 * <p>Admin 콘솔(PR-T3)은 {@link #search} 로 다중 필터 list 를 가져온다.</p>
 */
@Repository
public interface NotificationTemplateRepository
        extends JpaRepository<NotificationTemplate, Long>, JpaSpecificationExecutor<NotificationTemplate> {

    Optional<NotificationTemplate> findByTemplateCodeAndChannelAndLocale(
            String templateCode, NotificationChannel channel, String locale);

    /** Admin UI — 동일 template_code 의 모든 채널/언어 row. */
    List<NotificationTemplate> findByTemplateCodeOrderByChannelAscLocaleAsc(String templateCode);

    List<NotificationTemplate> findByChannelOrderByTemplateCodeAscLocaleAsc(NotificationChannel channel);

    /**
     * Admin 콘솔 list 검색 — code/채널/로케일/활성여부/카테고리/역할 다중 필터.
     * 모든 파라미터 nullable, null 이면 해당 필터 무시.
     */
    @Query("""
            SELECT t FROM NotificationTemplate t
            WHERE (:code IS NULL OR t.templateCode LIKE CONCAT('%', :code, '%'))
              AND (:channel IS NULL OR t.channel = :channel)
              AND (:locale IS NULL OR t.locale = :locale)
              AND (:enabled IS NULL OR t.enabled = :enabled)
              AND (:category IS NULL OR t.category = :category)
              AND (:recipientRoleLike IS NULL OR t.recipientRoles LIKE CONCAT('%', :recipientRoleLike, '%'))
            ORDER BY t.templateCode ASC, t.channel ASC, t.locale ASC
            """)
    Page<NotificationTemplate> search(@Param("code") String code,
                                      @Param("channel") NotificationChannel channel,
                                      @Param("locale") String locale,
                                      @Param("enabled") Boolean enabled,
                                      @Param("category") NotificationCategory category,
                                      @Param("recipientRoleLike") String recipientRoleLike,
                                      Pageable pageable);
}
