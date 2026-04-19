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
                /* selectedKva */ 500, /* quoteAmount */ new BigDecimal("3000.00"), /* sldFee */ null);

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
                /* selectedKva */ 45, /* quoteAmount */ new BigDecimal("350.00"), null);

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
    void confirmKva_이미_CONFIRMED인데_force_false면_거부() {
        Application app = newApp(KvaStatus.CONFIRMED, 100, new BigDecimal("650.00"));
        User admin = Mockito.mock(User.class);

        assertThatThrownBy(() -> app.confirmKva(200, new BigDecimal("1200.00"), admin, false))
                .isInstanceOf(IllegalStateException.class);
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
}
