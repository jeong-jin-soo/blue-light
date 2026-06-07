package com.bluelight.backend.domain.notification;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 사용자별 알림 환경설정 — (user, event_type, channel) 단위 ON/OFF.
 *
 * <p>행이 없으면 system_settings 측 채널 기본값을 따른다 (Single Source of Truth,
 * CLAUDE.md §설계 원칙). 즉 본 테이블은 "기본값과 다른 사용자 선호"만 보관한다.</p>
 */
@Entity
@Table(
        name = "notification_preferences",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_notif_pref",
                        columnNames = {"user_seq", "event_type", "channel"})
        },
        indexes = {
                @Index(name = "idx_notif_pref_user", columnList = "user_seq")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE notification_preferences SET deleted_at = NOW() WHERE preference_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class NotificationPreference extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "preference_seq")
    private Long preferenceSeq;

    @Column(name = "user_seq", nullable = false)
    private Long userSeq;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Builder
    public NotificationPreference(Long userSeq, String eventType, NotificationChannel channel, boolean enabled) {
        this.userSeq = userSeq;
        this.eventType = eventType;
        this.channel = channel;
        this.enabled = enabled;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }
}
