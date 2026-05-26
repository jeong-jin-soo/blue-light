package com.bluelight.backend.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotificationTemplateHistory 빌더 단위 테스트 (PR-T1).
 *
 * <p>append-only 엔티티 — 빌드 시 모든 필드가 영속되고 changedAt 이 자동 설정되는지 검증.
 * 상태 전이 메서드가 없으므로(불변) 빌더 검증으로 한정.</p>
 */
@DisplayName("NotificationTemplateHistory - PR-T1")
class NotificationTemplateHistoryTest {

    @Test
    @DisplayName("빌더 - 모든 필드 영속 + changedAt 자동 설정")
    void builder_persistsAllFields() {
        NotificationTemplateHistory history = NotificationTemplateHistory.builder()
                .templateSeq(42L)
                .changeType(TemplateChangeType.PUBLISH)
                .diffJson("{\"before\":{\"subject\":\"old\"},\"after\":{\"subject\":\"new\"}}")
                .beforeSnapshotJson("{\"subject\":\"old\",\"bodyText\":\"...\"}")
                .afterSnapshotJson("{\"subject\":\"new\",\"bodyText\":\"...\"}")
                .changeReason("법무팀 요청 — opt-out 링크 위치 변경")
                .actorUserSeq(9001L)
                .actorIp("203.0.113.42")
                .build();

        assertThat(history.getTemplateSeq()).isEqualTo(42L);
        assertThat(history.getChangeType()).isEqualTo(TemplateChangeType.PUBLISH);
        assertThat(history.getDiffJson()).contains("\"before\"").contains("\"after\"");
        assertThat(history.getBeforeSnapshotJson()).contains("old");
        assertThat(history.getAfterSnapshotJson()).contains("new");
        assertThat(history.getChangeReason()).isEqualTo("법무팀 요청 — opt-out 링크 위치 변경");
        assertThat(history.getActorUserSeq()).isEqualTo(9001L);
        assertThat(history.getActorIp()).isEqualTo("203.0.113.42");
        assertThat(history.getChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("빌더 - changeReason nullable (D-6 — SECURITY/PAYMENT/MARKETING 외 카테고리)")
    void builder_changeReasonNullable() {
        NotificationTemplateHistory history = NotificationTemplateHistory.builder()
                .templateSeq(43L)
                .changeType(TemplateChangeType.ENABLE)
                .diffJson("{\"before\":{\"enabled\":false},\"after\":{\"enabled\":true}}")
                .beforeSnapshotJson("{\"enabled\":false}")
                .afterSnapshotJson("{\"enabled\":true}")
                .changeReason(null)
                .actorUserSeq(9001L)
                .actorIp(null)
                .build();

        assertThat(history.getChangeReason()).isNull();
        assertThat(history.getActorIp()).isNull();
        assertThat(history.getChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("빌더 - IPv6 actorIp 수용 (VARCHAR(45))")
    void builder_acceptsIpv6() {
        String ipv6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";

        NotificationTemplateHistory history = NotificationTemplateHistory.builder()
                .templateSeq(44L)
                .changeType(TemplateChangeType.ROLLBACK)
                .diffJson("{}")
                .beforeSnapshotJson("{}")
                .afterSnapshotJson("{}")
                .changeReason("v3 으로 롤백")
                .actorUserSeq(9001L)
                .actorIp(ipv6)
                .build();

        assertThat(history.getActorIp()).isEqualTo(ipv6);
    }
}
