package com.bluelight.backend.api.admin.notification.template.dto;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.OutboxStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * PR-T7 P1 — 알림 템플릿 30일 발송 메트릭스 응답.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §6.2
 * ({@code GET /metrics?days=30}), §11.4 (성능 SLA).</p>
 *
 * <p>관리자 콘솔 Edit 화면 헤더에 "지난 30일 1,204회 발송 · 실패 0.3% · 누락변수 5건"
 * 형태로 표시되어 운영자가 카피 정정 시점을 즉시 판단할 수 있게 한다.</p>
 *
 * <p>{@code totalSent} 등 합계는 채널 무관 전체. {@code byChannel} 은 동일 데이터의
 * 채널별 분해. 모두 {@code is_test=false} 운영 발송만 집계 (어드민 테스트 제외).</p>
 *
 * @param templateCode    집계 대상 템플릿 코드 (예: "A-17")
 * @param days            집계 기간 (일)
 * @param since           집계 시작 시각 ({@code now - days})
 * @param totalCount      기간 내 전체 outbox row 수
 * @param totalSent       SENT 카운트
 * @param totalFailed     FAILED + DEAD 카운트 (재시도 가능/불가 합산)
 * @param totalSkipped    SKIPPED 카운트 (옵트아웃, feature flag 등 가드 컷)
 * @param totalPending    PENDING + SENDING 카운트 (아직 처리 안 됨)
 * @param renderWarnings  변수 치환 경고가 있던 row 수 (운영자 액션 필요 지표)
 * @param failureRate     실패율 = (failed + dead) / (sent + failed + dead). 0~1, sent+failed=0 이면 0
 * @param byChannel       채널별 분해
 */
public record TemplateMetricsResponse(
        String templateCode,
        int days,
        LocalDateTime since,
        long totalCount,
        long totalSent,
        long totalFailed,
        long totalSkipped,
        long totalPending,
        long renderWarnings,
        double failureRate,
        List<ChannelBreakdown> byChannel
) {

    /**
     * 채널별 발송 분해.
     *
     * @param channel       알림 채널 (IN_APP / EMAIL / SMS / WHATSAPP)
     * @param sent          SENT 카운트
     * @param failed        FAILED + DEAD 카운트
     * @param skipped       SKIPPED 카운트
     * @param pending       PENDING + SENDING 카운트
     * @param failureRate   해당 채널의 실패율
     */
    public record ChannelBreakdown(
            NotificationChannel channel,
            long sent,
            long failed,
            long skipped,
            long pending,
            double failureRate
    ) {
        public static ChannelBreakdown of(NotificationChannel channel, Map<OutboxStatus, Long> counts) {
            long sent = counts.getOrDefault(OutboxStatus.SENT, 0L);
            long failed = counts.getOrDefault(OutboxStatus.FAILED, 0L)
                    + counts.getOrDefault(OutboxStatus.DEAD, 0L);
            long skipped = counts.getOrDefault(OutboxStatus.SKIPPED, 0L);
            long pending = counts.getOrDefault(OutboxStatus.PENDING, 0L)
                    + counts.getOrDefault(OutboxStatus.SENDING, 0L);
            double rate = (sent + failed) == 0 ? 0.0 : (double) failed / (sent + failed);
            return new ChannelBreakdown(channel, sent, failed, skipped, pending, rate);
        }
    }
}
