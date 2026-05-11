package com.bluelight.backend.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 알림 템플릿 카탈로그 Repository.
 *
 * <p>Orchestrator 는 (template_code, channel, locale) 키로 본 Repository 를 조회한다.
 * locale 미스 시 영어(en) 폴백은 호출 측에서 처리.</p>
 */
@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByTemplateCodeAndChannelAndLocale(
            String templateCode, NotificationChannel channel, String locale);

    /** Admin UI — 동일 template_code 의 모든 채널/언어 row. */
    List<NotificationTemplate> findByTemplateCodeOrderByChannelAscLocaleAsc(String templateCode);

    List<NotificationTemplate> findByChannelOrderByTemplateCodeAscLocaleAsc(NotificationChannel channel);
}
