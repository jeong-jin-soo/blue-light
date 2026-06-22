package com.bluelight.backend.domain.application;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 종결(COMPLETED/EXPIRED) 건 쓰기 차단 — {@link Application#isTerminal()} / {@link Application#assertModifiable()}.
 *
 * <p>신청자·LEW 의 모든 파일 업로드/수정 경로가 종결 상태에서 막혀야 한다.
 * 도메인 가드가 단일 진실원천이며, 각 쓰기 서비스가 이 메서드를 호출한다.</p>
 */
class ApplicationTerminalGuardTest {

    private Application newApp() {
        return Application.builder()
                .user(Mockito.mock(User.class))
                .address("1 Blk Test")
                .postalCode("560001")
                .buildingType("HDB_FLAT")
                .quoteAmount(new BigDecimal("650.00"))
                .build(); // status = PENDING_REVIEW
    }

    @Test
    void COMPLETED_는_isTerminal_이고_쓰기_차단() {
        Application app = newApp();
        app.issueLicense("L-12345", LocalDate.now().plusYears(1)); // → COMPLETED

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.COMPLETED);
        assertThat(app.isTerminal()).isTrue();
        assertThatThrownBy(app::assertModifiable)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "APPLICATION_TERMINAL");
    }

    @Test
    void EXPIRED_는_isTerminal_이고_쓰기_차단() {
        Application app = newApp();
        app.markAsExpired(); // → EXPIRED

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.EXPIRED);
        assertThat(app.isTerminal()).isTrue();
        assertThatThrownBy(app::assertModifiable)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "APPLICATION_TERMINAL");
    }

    @Test
    void PENDING_REVIEW_는_비종결_이고_쓰기_허용() {
        Application app = newApp();

        assertThat(app.isTerminal()).isFalse();
        assertThatCode(app::assertModifiable).doesNotThrowAnyException();
    }

    @Test
    void IN_PROGRESS_는_비종결_이고_쓰기_허용() {
        Application app = newApp();
        app.startInspection(); // → IN_PROGRESS

        assertThat(app.isTerminal()).isFalse();
        assertThatCode(app::assertModifiable).doesNotThrowAnyException();
    }

    @Test
    void PAID_는_비종결_이고_쓰기_허용() {
        Application app = newApp();
        app.markAsPaid(); // → PAID

        assertThat(app.isTerminal()).isFalse();
        assertThatCode(app::assertModifiable).doesNotThrowAnyException();
    }
}
