package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplateMetricsResponse;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR-T7 P1 — 알림 템플릿 발송 메트릭스 계산 서비스.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §4.2 P1,
 * §6.2 ({@code GET /metrics?days=30}).</p>
 *
 * <p>{@code NotificationOutbox} 의 (template_code, channel, status) 집계 + render_warnings 카운트.
 * 운영 발송만 ({@code is_test=false}) 집계. 테스트 발송은 {@code admin/notifications/delivery}
 * 별도 필터에서 본다.</p>
 *
 * <p><b>성능</b>: 두 쿼리 모두 {@code idx_notif_outbox_template_code} +
 * {@code created_at} 인덱스를 활용하므로 30일 윈도우 기준 ms 단위 응답. 캐싱 미적용
 * (편집 즉시 반영 UX 우선, 스펙 §11.1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateMetricsService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;

    private final NotificationTemplateRepository templateRepository;
    private final NotificationOutboxRepository outboxRepository;

    /**
     * 템플릿 메트릭스 집계.
     *
     * @param templateSeq 대상 템플릿 seq
     * @param days        집계 기간 (1~90, 범위 외 입력은 clamp)
     * @return 채널 전체 합계 + 채널별 분해 + render warnings count
     * @throws NotificationTemplateAdminService.TemplateNotFoundException 템플릿 미존재
     */
    public TemplateMetricsResponse computeMetrics(Long templateSeq, int days) {
        int clamped = Math.min(MAX_DAYS, Math.max(MIN_DAYS, days));
        NotificationTemplate template = templateRepository.findById(templateSeq)
                .orElseThrow(() -> new NotificationTemplateAdminService.TemplateNotFoundException(templateSeq));

        LocalDateTime since = LocalDateTime.now().minusDays(clamped);
        String code = template.getTemplateCode();

        // 1. 채널 × 상태 집계
        List<Object[]> rows = outboxRepository.aggregateChannelStatusByTemplate(code, since);
        Map<NotificationChannel, Map<OutboxStatus, Long>> byChannel =
                new EnumMap<>(NotificationChannel.class);
        for (Object[] row : rows) {
            NotificationChannel channel = (NotificationChannel) row[0];
            OutboxStatus status = (OutboxStatus) row[1];
            Long count = ((Number) row[2]).longValue();
            byChannel.computeIfAbsent(channel, c -> new TreeMap<>()).put(status, count);
        }

        // 2. 채널별 breakdown 생성 + 전체 합계 누적
        long totalSent = 0, totalFailed = 0, totalSkipped = 0, totalPending = 0;
        List<TemplateMetricsResponse.ChannelBreakdown> breakdowns =
                new java.util.ArrayList<>(byChannel.size());
        for (Map.Entry<NotificationChannel, Map<OutboxStatus, Long>> e : byChannel.entrySet()) {
            TemplateMetricsResponse.ChannelBreakdown b =
                    TemplateMetricsResponse.ChannelBreakdown.of(e.getKey(), e.getValue());
            breakdowns.add(b);
            totalSent += b.sent();
            totalFailed += b.failed();
            totalSkipped += b.skipped();
            totalPending += b.pending();
        }

        // 3. render warnings 카운트
        long renderWarnings = outboxRepository.countRenderWarningsByTemplate(code, since);

        long totalCount = totalSent + totalFailed + totalSkipped + totalPending;
        double failureRate = (totalSent + totalFailed) == 0
                ? 0.0
                : (double) totalFailed / (totalSent + totalFailed);

        log.debug("Template metrics computed: code={}, days={}, total={}, failed={}, warnings={}",
                code, clamped, totalCount, totalFailed, renderWarnings);

        return new TemplateMetricsResponse(
                code,
                clamped,
                since,
                totalCount,
                totalSent,
                totalFailed,
                totalSkipped,
                totalPending,
                renderWarnings,
                failureRate,
                breakdowns
        );
    }
}
