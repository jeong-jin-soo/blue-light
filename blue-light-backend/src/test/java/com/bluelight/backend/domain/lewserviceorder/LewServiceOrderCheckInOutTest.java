package com.bluelight.backend.domain.lewserviceorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * LEW Service 방문형 리스키닝 PR 3 — checkIn/checkOut/requestRevisit/legacy 어댑터 단위 테스트.
 */
class LewServiceOrderCheckInOutTest {

    private LewServiceOrder newOrderWithStatus(LewServiceOrderStatus status) {
        LewServiceOrder order = LewServiceOrder.builder().build();
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

    // ── checkIn ───────────────────────────────────────────

    @Test
    @DisplayName("VISIT_SCHEDULED 에서 checkIn → checkInAt 세팅, status 불변")
    void checkIn_성공() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        order.checkIn();
        assertThat(order.getCheckInAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_SCHEDULED);
        assertThat(order.isOnSite()).isTrue(); // ON_SITE 파생 상태
    }

    @ParameterizedTest
    @EnumSource(value = LewServiceOrderStatus.class,
            names = {"PENDING_QUOTE", "QUOTE_PROPOSED", "QUOTE_REJECTED",
                    "PENDING_PAYMENT", "PAID", "VISIT_COMPLETED",
                    "REVISIT_REQUESTED", "COMPLETED"})
    @DisplayName("VISIT_SCHEDULED 가 아닌 상태에서 checkIn → IllegalStateException")
    void checkIn_비허용_상태(LewServiceOrderStatus status) {
        LewServiceOrder order = newOrderWithStatus(status);
        assertThatIllegalStateException().isThrownBy(order::checkIn);
    }

    // ── checkOut ───────────────────────────────────────────

    @Test
    @DisplayName("checkIn 후 checkOut → VISIT_COMPLETED 로 전이")
    void checkOut_성공() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        order.checkIn();

        order.checkOut(42L, "All lighting circuits tested OK");

        assertThat(order.getCheckOutAt()).isNotNull();
        assertThat(order.getVisitReportFileSeq()).isEqualTo(42L);
        // legacy 하위호환 mirror
        assertThat(order.getUploadedFileSeq()).isEqualTo(42L);
        assertThat(order.getManagerNote()).isEqualTo("All lighting circuits tested OK");
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_COMPLETED);
    }

    @Test
    @DisplayName("체크인 없이 checkOut → IllegalStateException")
    void checkOut_without_checkIn() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        assertThatIllegalStateException()
                .isThrownBy(() -> order.checkOut(1L, "note"))
                .withMessageContaining("prior check-in");
    }

    @Test
    @DisplayName("visitReportFileSeq null → IllegalArgumentException")
    void checkOut_with_null_file() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        order.checkIn();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> order.checkOut(null, "note"));
    }

    // ── requestRevisit ─────────────────────────────────────

    @Test
    @DisplayName("VISIT_COMPLETED 에서 requestRevisit → REVISIT_REQUESTED")
    void requestRevisit_성공() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_COMPLETED);
        order.requestRevisit("Socket #3 still has no power");

        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.REVISIT_REQUESTED);
        assertThat(order.getRevisitComment()).isEqualTo("Socket #3 still has no power");
    }

    // ── complete ───────────────────────────────────────────

    @Test
    @DisplayName("VISIT_COMPLETED 에서만 complete 가능")
    void complete_only_from_visit_completed() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_COMPLETED);
        order.complete();
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = LewServiceOrderStatus.class,
            names = {"PENDING_QUOTE", "QUOTE_PROPOSED", "QUOTE_REJECTED",
                    "PENDING_PAYMENT", "PAID", "VISIT_SCHEDULED",
                    "REVISIT_REQUESTED", "COMPLETED"})
    @DisplayName("VISIT_COMPLETED 가 아닌 상태에서 complete → IllegalStateException")
    void complete_비허용_상태(LewServiceOrderStatus status) {
        LewServiceOrder order = newOrderWithStatus(status);
        assertThatIllegalStateException().isThrownBy(order::complete);
    }

    // ── legacy 어댑터 ───────────────────────────────────────

    @Test
    @DisplayName("legacy uploadDeliverable: PAID 에서 호출 → VISIT_COMPLETED 로 한 번에 전이")
    void legacy_from_paid() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.PAID);
        order.legacyUploadDeliverable(7L, "legacy");

        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_COMPLETED);
        assertThat(order.getCheckInAt()).isNotNull();
        assertThat(order.getCheckOutAt()).isNotNull();
        assertThat(order.getVisitReportFileSeq()).isEqualTo(7L);
        assertThat(order.getUploadedFileSeq()).isEqualTo(7L);
    }

    @Test
    @DisplayName("legacy uploadDeliverable: VISIT_SCHEDULED + checkIn 없음 → checkIn 보강 후 VISIT_COMPLETED")
    void legacy_from_visit_scheduled_no_checkin() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_SCHEDULED);
        order.legacyUploadDeliverable(8L, null);
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_COMPLETED);
        assertThat(order.getCheckInAt()).isNotNull();
    }

    @Test
    @DisplayName("legacy uploadDeliverable: REVISIT_REQUESTED 에서도 재방문 결과로 작동")
    void legacy_from_revisit_requested() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.REVISIT_REQUESTED);
        order.legacyUploadDeliverable(9L, "revisit done");
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_COMPLETED);
    }

    @Test
    @DisplayName("legacy uploadDeliverable: VISIT_COMPLETED 에서 재제출 — 파일 갱신, 상태 유지")
    void legacy_resubmit_from_visit_completed() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.VISIT_COMPLETED);
        // checkInAt 은 비어있을 수 있음 (legacy 데이터)
        order.legacyUploadDeliverable(10L, "updated");
        assertThat(order.getStatus()).isEqualTo(LewServiceOrderStatus.VISIT_COMPLETED);
        assertThat(order.getVisitReportFileSeq()).isEqualTo(10L);
        assertThat(order.getCheckOutAt()).isNotNull();
    }

    @Test
    @DisplayName("legacy uploadDeliverable: COMPLETED 에서는 호출 차단")
    void legacy_from_completed_rejected() {
        LewServiceOrder order = newOrderWithStatus(LewServiceOrderStatus.COMPLETED);
        assertThatIllegalStateException()
                .isThrownBy(() -> order.legacyUploadDeliverable(1L, null));
    }
}
