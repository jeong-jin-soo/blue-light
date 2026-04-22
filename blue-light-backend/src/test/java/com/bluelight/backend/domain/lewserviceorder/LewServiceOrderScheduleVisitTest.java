package com.bluelight.backend.domain.lewserviceorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * LEW Service 방문형 리스키닝 PR 2 — {@link LewServiceOrder#scheduleVisit} 단위 테스트.
 *
 * <p>핵심 검증:
 * <ol>
 *   <li>PAID / IN_PROGRESS / REVISION_REQUESTED 상태에서만 호출 가능</li>
 *   <li>상태 전이는 발생하지 않음 (status 불변)</li>
 *   <li>null datetime 거부</li>
 *   <li>재호출 시 값 덮어쓰기 (재예약 지원)</li>
 * </ol>
 */
class LewServiceOrderScheduleVisitTest {

    private LewServiceOrder newOrderWithStatus(LewServiceOrderStatus status) {
        LewServiceOrder order = LewServiceOrder.builder().build();
        // User가 필수이지만 scheduleVisit 은 user 를 참조하지 않으므로 리플렉션으로 status 만 주입.
        setField(order, "status", status);
        return order;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = LewServiceOrder.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @ParameterizedTest
    @EnumSource(value = LewServiceOrderStatus.class,
            names = {"PAID", "IN_PROGRESS", "REVISION_REQUESTED"})
    @DisplayName("허용 상태에서는 일정이 세팅되고 status 는 불변")
    void 허용_상태에서_일정_세팅(LewServiceOrderStatus status) {
        LewServiceOrder order = newOrderWithStatus(status);
        LocalDateTime when = LocalDateTime.of(2026, 5, 1, 14, 30);

        order.scheduleVisit(when, "Please call on arrival");

        assertThat(order.getVisitScheduledAt()).isEqualTo(when);
        assertThat(order.getVisitScheduleNote()).isEqualTo("Please call on arrival");
        assertThat(order.getStatus()).isEqualTo(status);  // 상태 전이 없음
    }

    @ParameterizedTest
    @EnumSource(value = LewServiceOrderStatus.class,
            names = {"PENDING_QUOTE", "QUOTE_PROPOSED", "QUOTE_REJECTED",
                    "PENDING_PAYMENT", "SLD_UPLOADED", "COMPLETED"})
    @DisplayName("허용되지 않은 상태에서는 IllegalStateException")
    void 비허용_상태에서_예외(LewServiceOrderStatus status) {
        LewServiceOrder order = newOrderWithStatus(status);
        LocalDateTime when = LocalDateTime.of(2026, 5, 1, 14, 30);

        assertThatIllegalStateException()
                .isThrownBy(() -> order.scheduleVisit(when, "x"))
                .withMessageContaining("Visit can only be scheduled");
    }

    @Test
    @DisplayName("visitScheduledAt null 이면 IllegalArgumentException")
    void null_일정은_거부() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.PAID);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> order.scheduleVisit(null, "x"));
    }

    @Test
    @DisplayName("재호출 시 값 덮어쓰기 (재예약 가능)")
    void 재예약_지원() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.PAID);
        LocalDateTime first = LocalDateTime.of(2026, 5, 1, 10, 0);
        LocalDateTime second = LocalDateTime.of(2026, 5, 3, 15, 0);

        order.scheduleVisit(first, "first note");
        order.scheduleVisit(second, "rescheduled");

        assertThat(order.getVisitScheduledAt()).isEqualTo(second);
        assertThat(order.getVisitScheduleNote()).isEqualTo("rescheduled");
    }

    @Test
    @DisplayName("note 는 nullable — null 로도 덮어쓸 수 있어야 함")
    void null_note_허용() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.PAID);
        LocalDateTime when = LocalDateTime.of(2026, 5, 1, 10, 0);

        order.scheduleVisit(when, null);

        assertThat(order.getVisitScheduledAt()).isEqualTo(when);
        assertThat(order.getVisitScheduleNote()).isNull();
    }
}
