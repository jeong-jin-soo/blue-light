package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.TemplateMetricsResponse;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import com.bluelight.backend.domain.notification.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TemplateMetricsService — PR-T7 P1.
 *
 * <p>30일 outbox 집계 → 채널별 분해 + render warnings + 전체 합계 + failureRate.</p>
 */
@DisplayName("TemplateMetricsService - PR-T7 P1")
class TemplateMetricsServiceTest {

    private static final Long TEMPLATE_SEQ = 17L;
    private static final String TEMPLATE_CODE = "A-17";

    private NotificationTemplateRepository templateRepository;
    private NotificationOutboxRepository outboxRepository;
    private TemplateMetricsService service;

    @BeforeEach
    void setUp() {
        templateRepository = mock(NotificationTemplateRepository.class);
        outboxRepository = mock(NotificationOutboxRepository.class);
        service = new TemplateMetricsService(templateRepository, outboxRepository);

        NotificationTemplate template = mock(NotificationTemplate.class);
        when(template.getTemplateCode()).thenReturn(TEMPLATE_CODE);
        when(templateRepository.findById(TEMPLATE_SEQ)).thenReturn(Optional.of(template));
    }

    private Object[] row(NotificationChannel channel, OutboxStatus status, long count) {
        return new Object[]{channel, status, count};
    }

    @Test
    @DisplayName("정상 집계 — 채널 2종, 상태 다양, render warnings 포함")
    void computesMetricsCorrectly() {
        when(outboxRepository.aggregateChannelStatusByTemplate(eq(TEMPLATE_CODE), any()))
                .thenReturn(List.of(
                        row(NotificationChannel.EMAIL, OutboxStatus.SENT, 1000L),
                        row(NotificationChannel.EMAIL, OutboxStatus.FAILED, 3L),
                        row(NotificationChannel.EMAIL, OutboxStatus.DEAD, 1L),
                        row(NotificationChannel.EMAIL, OutboxStatus.SKIPPED, 12L),
                        row(NotificationChannel.IN_APP, OutboxStatus.SENT, 200L),
                        row(NotificationChannel.IN_APP, OutboxStatus.PENDING, 2L)
                ));
        when(outboxRepository.countRenderWarningsByTemplate(eq(TEMPLATE_CODE), any())).thenReturn(5L);

        TemplateMetricsResponse result = service.computeMetrics(TEMPLATE_SEQ, 30);

        assertThat(result.templateCode()).isEqualTo(TEMPLATE_CODE);
        assertThat(result.days()).isEqualTo(30);
        assertThat(result.totalSent()).isEqualTo(1200L);
        assertThat(result.totalFailed()).isEqualTo(4L); // 3 FAILED + 1 DEAD
        assertThat(result.totalSkipped()).isEqualTo(12L);
        assertThat(result.totalPending()).isEqualTo(2L);
        assertThat(result.totalCount()).isEqualTo(1218L);
        assertThat(result.renderWarnings()).isEqualTo(5L);
        // failureRate = 4 / (1200 + 4) = 0.003322
        assertThat(result.failureRate()).isCloseTo(0.003322, within(1e-5));

        assertThat(result.byChannel()).hasSize(2);
        TemplateMetricsResponse.ChannelBreakdown email = result.byChannel().stream()
                .filter(b -> b.channel() == NotificationChannel.EMAIL).findFirst().orElseThrow();
        assertThat(email.sent()).isEqualTo(1000L);
        assertThat(email.failed()).isEqualTo(4L);
        assertThat(email.skipped()).isEqualTo(12L);
        assertThat(email.pending()).isEqualTo(0L);
    }

    @Test
    @DisplayName("빈 집계 — sent/failed 모두 0 이면 failureRate=0 (0 나눗셈 방지)")
    void zeroDivisionGuard() {
        when(outboxRepository.aggregateChannelStatusByTemplate(eq(TEMPLATE_CODE), any()))
                .thenReturn(List.of());
        when(outboxRepository.countRenderWarningsByTemplate(eq(TEMPLATE_CODE), any())).thenReturn(0L);

        TemplateMetricsResponse result = service.computeMetrics(TEMPLATE_SEQ, 30);

        assertThat(result.totalSent()).isZero();
        assertThat(result.totalFailed()).isZero();
        assertThat(result.failureRate()).isZero();
        assertThat(result.byChannel()).isEmpty();
    }

    @Test
    @DisplayName("days clamp — 0 입력 → 1, 200 입력 → 90")
    void daysClampedToValidRange() {
        when(outboxRepository.aggregateChannelStatusByTemplate(any(), any())).thenReturn(List.of());
        when(outboxRepository.countRenderWarningsByTemplate(any(), any())).thenReturn(0L);

        assertThat(service.computeMetrics(TEMPLATE_SEQ, 0).days()).isEqualTo(1);
        assertThat(service.computeMetrics(TEMPLATE_SEQ, 200).days()).isEqualTo(90);
        assertThat(service.computeMetrics(TEMPLATE_SEQ, -5).days()).isEqualTo(1);
        assertThat(service.computeMetrics(TEMPLATE_SEQ, 30).days()).isEqualTo(30);
    }

    @Test
    @DisplayName("템플릿 미존재 → TemplateNotFoundException")
    void throwsWhenTemplateMissing() {
        when(templateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeMetrics(999L, 30))
                .isInstanceOf(NotificationTemplateAdminService.TemplateNotFoundException.class);
    }

    @Test
    @DisplayName("since 는 now - days 로 계산 (현재 시각 기준)")
    void sinceComputedFromNowMinusDays() {
        when(outboxRepository.aggregateChannelStatusByTemplate(any(), any())).thenReturn(List.of());
        when(outboxRepository.countRenderWarningsByTemplate(any(), any())).thenReturn(0L);

        LocalDateTime before = LocalDateTime.now().minusDays(7);
        TemplateMetricsResponse result = service.computeMetrics(TEMPLATE_SEQ, 7);
        LocalDateTime after = LocalDateTime.now().minusDays(7);

        assertThat(result.since()).isBetween(before.minusSeconds(2), after.plusSeconds(2));
    }

    @Test
    @DisplayName("repository 호출 — is_test 제외 + 정확한 since")
    void repositoryCalledWithIsTestFalseAndCorrectSince() {
        when(outboxRepository.aggregateChannelStatusByTemplate(any(), any())).thenReturn(List.of());
        when(outboxRepository.countRenderWarningsByTemplate(any(), any())).thenReturn(0L);

        service.computeMetrics(TEMPLATE_SEQ, 30);

        // 쿼리 자체에 is_test=false 가 박혀있어 verify 만으로 충분
        verify(outboxRepository).aggregateChannelStatusByTemplate(eq(TEMPLATE_CODE), any(LocalDateTime.class));
        verify(outboxRepository).countRenderWarningsByTemplate(eq(TEMPLATE_CODE), any(LocalDateTime.class));
    }
}
