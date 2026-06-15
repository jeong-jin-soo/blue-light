package com.bluelight.backend.domain.application;

import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5 PR#1 — {@link Application} 도메인 메서드 검증.
 *
 * <ul>
 *   <li>재제출 허점: {@code updateDetails} 가 CONFIRMED 상태에서 selectedKva 변경을 무시</li>
 *   <li>{@code confirmKva} 는 UNKNOWN → CONFIRMED, 기본 force=false 로는 재호출 불가</li>
 *   <li>{@code confirmKva(force=true)} 는 이미 CONFIRMED 여도 덮어쓰기</li>
 * </ul>
 */
class ApplicationKvaDomainTest {

    private Application newApp(KvaStatus kvaStatus, Integer kva, BigDecimal quote) {
        User user = Mockito.mock(User.class);
        Application app = Application.builder()
                .user(user)
                .address("1 Blk Test")
                .postalCode("560001")
                .buildingType("HDB_FLAT")
                .selectedKva(kva)
                .quoteAmount(quote)
                .kvaStatus(kvaStatus)
                .kvaSource(kvaStatus == KvaStatus.CONFIRMED ? KvaSource.USER_INPUT : null)
                .build();
        return app;
    }

    @Test
    void updateDetails_CONFIRMED_상태에서는_selectedKva_변경_무시() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));

        app.updateDetails("New Addr", "560002", "SHOPHOUSE",
                /* selectedKva */ 500, /* quoteAmount */ new BigDecimal("3000.00"), /* sldFee */ null,
                /* calloutFee */ null);

        assertThat(app.getAddress()).isEqualTo("New Addr");
        assertThat(app.getPostalCode()).isEqualTo("560002");
        assertThat(app.getBuildingType()).isEqualTo("SHOPHOUSE");
        // 가격 우회 시도는 거부: 기존 100 kVA / 650 유지
        assertThat(app.getSelectedKva()).isEqualTo(100);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("650.00");
    }

    @Test
    void updateDetails_UNKNOWN_상태에서는_selectedKva_변경_허용() {
        Application app = newApp(KvaStatus.UNKNOWN, 45, new BigDecimal("350.00"));

        app.updateDetails("Addr", "560001", "HDB_FLAT",
                /* selectedKva */ 45, /* quoteAmount */ new BigDecimal("350.00"), null, null);

        assertThat(app.getSelectedKva()).isEqualTo(45);
        assertThat(app.getKvaStatus()).isEqualTo(KvaStatus.UNKNOWN);
    }

    @Test
    void confirmKva_UNKNOWN에서_CONFIRMED로_전환_성공() {
        Application app = newApp(KvaStatus.UNKNOWN, 45, new BigDecimal("350.00"));
        User lew = Mockito.mock(User.class);

        app.confirmKva(100, new BigDecimal("650.00"), lew, false);

        assertThat(app.getKvaStatus()).isEqualTo(KvaStatus.CONFIRMED);
        assertThat(app.getKvaSource()).isEqualTo(KvaSource.LEW_VERIFIED);
        assertThat(app.getSelectedKva()).isEqualTo(100);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("650.00");
        assertThat(app.getKvaConfirmedBy()).isSameAs(lew);
        assertThat(app.getKvaConfirmedAt()).isNotNull();
    }

    @Test
    void confirmKva_이미_CONFIRMED여도_재확정_변경_성공() {
        // 결제 전에는 LEW 가 확정된 값을 force 없이도 다시 변경·재확정할 수 있다(PAID 차단은 서비스 책임).
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        User lew = Mockito.mock(User.class);

        app.confirmKva(200, new BigDecimal("1200.00"), lew, false);

        assertThat(app.getKvaStatus()).isEqualTo(KvaStatus.CONFIRMED);
        assertThat(app.getSelectedKva()).isEqualTo(200);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("1200.00");
        assertThat(app.getKvaSource()).isEqualTo(KvaSource.LEW_VERIFIED);
        assertThat(app.getKvaConfirmedBy()).isSameAs(lew);
    }

    @Test
    void confirmKva_force_true면_CONFIRMED_상태에서도_덮어쓰기() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        User admin = Mockito.mock(User.class);

        app.confirmKva(200, new BigDecimal("1200.00"), admin, true);

        assertThat(app.getSelectedKva()).isEqualTo(200);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("1200.00");
        assertThat(app.getKvaSource()).isEqualTo(KvaSource.LEW_VERIFIED);
    }

    // ── PR-1: 결제 후 kVA 사후 변경 도메인 메서드 ────────────────

    @Test
    void overrideKvaPostPayment_PAID_상태에서_변경_성공() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        // 도메인 transition: PENDING_REVIEW → … → PAID 까지 끌어올림
        app.approveForPayment(); // PENDING_PAYMENT
        app.markAsPaid();        // PAID
        User admin = Mockito.mock(User.class);

        app.overrideKvaPostPayment(200, new BigDecimal("1200.00"), admin);

        assertThat(app.getSelectedKva()).isEqualTo(200);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("1200.00");
        assertThat(app.getKvaConfirmedBy()).isSameAs(admin);
        assertThat(app.getKvaConfirmedAt()).isNotNull();
        // status 는 PAID 그대로 유지 (PR3 모델)
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PAID);
        // kvaStatus 는 CONFIRMED 그대로
        assertThat(app.getKvaStatus()).isEqualTo(KvaStatus.CONFIRMED);
        // kvaSource 는 보존 (USER_INPUT 그대로)
        assertThat(app.getKvaSource()).isEqualTo(KvaSource.USER_INPUT);
    }

    @Test
    void overrideKvaPostPayment_IN_PROGRESS_상태에서도_허용() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        app.approveForPayment();
        app.markAsPaid();
        app.startInspection();
        User admin = Mockito.mock(User.class);

        app.overrideKvaPostPayment(150, new BigDecimal("950.00"), admin);

        assertThat(app.getSelectedKva()).isEqualTo(150);
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.IN_PROGRESS);
    }

    @Test
    void overrideKvaPostPayment_PRE_PAYMENT_상태에서_거부() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        // 기본 PENDING_REVIEW
        User admin = Mockito.mock(User.class);

        assertThatThrownBy(() -> app.overrideKvaPostPayment(200, new BigDecimal("1200"), admin))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overrideKvaPostPayment_EXPIRED_상태에서_거부() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        app.approveForPayment();
        app.markAsPaid();
        app.markAsExpired();
        User admin = Mockito.mock(User.class);

        assertThatThrownBy(() -> app.overrideKvaPostPayment(200, new BigDecimal("1200"), admin))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void overrideKvaPostPayment_null_인자는_거부() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        app.approveForPayment();
        app.markAsPaid();
        User admin = Mockito.mock(User.class);

        assertThatThrownBy(() -> app.overrideKvaPostPayment(null, new BigDecimal("1200"), admin))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> app.overrideKvaPostPayment(200, null, admin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isPostPaymentStatus_PAID_IN_PROGRESS_COMPLETED만_true() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        assertThat(app.isPostPaymentStatus()).isFalse(); // PENDING_REVIEW

        app.approveForPayment();
        assertThat(app.isPostPaymentStatus()).isFalse(); // PENDING_PAYMENT

        app.markAsPaid();
        assertThat(app.isPostPaymentStatus()).isTrue();  // PAID

        app.startInspection();
        assertThat(app.isPostPaymentStatus()).isTrue();  // IN_PROGRESS

        app.issueLicense("LIC-001", java.time.LocalDate.now().plusYears(1));
        assertThat(app.isPostPaymentStatus()).isTrue();  // COMPLETED

        app.markAsExpired();
        assertThat(app.isPostPaymentStatus()).isFalse(); // EXPIRED
    }
}
