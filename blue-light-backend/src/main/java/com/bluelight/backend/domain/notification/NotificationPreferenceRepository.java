package com.bluelight.backend.domain.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 사용자 알림 환경설정 Repository.
 *
 * <p>행이 없으면 기본값을 따른다 — 본 Repository 는 "기본값과 다른 사용자 선호"만 조회한다.</p>
 */
@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByUserSeqAndEventTypeAndChannel(
            Long userSeq, String eventType, NotificationChannel channel);

    List<NotificationPreference> findByUserSeq(Long userSeq);

    /** 환경설정 페이지 일괄 조회 — 사용자가 이미 변경한 (event, channel) 매트릭스. */
    List<NotificationPreference> findByUserSeqAndChannel(Long userSeq, NotificationChannel channel);
}
