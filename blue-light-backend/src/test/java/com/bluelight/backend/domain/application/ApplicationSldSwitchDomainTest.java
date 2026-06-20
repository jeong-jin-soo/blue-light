package com.bluelight.backend.domain.application;

import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Application#switchSldToLewCreated} 도메인 검증.
 * (sld-lew-conversion-fee-spec.md §3.1)
 */
class ApplicationSldSwitchDomainTest {

    private Application newApp(SldOption sldOption, BigDecimal sldFee, BigDecimal quote) {
        User user = Mockito.mock(User.class);
        return Application.builder()
                .user(user)
                .address("1 Blk Test")
                .postalCode("560001")
                .buildingType("HDB_FLAT")
                .selectedKva(100)
                .quoteAmount(quote)
                .sldOption(sldOption)
                .sldFee(sldFee)
                .kvaStatus(KvaStatus.CONFIRMED)
                .kvaSource(KvaSource.LEW_VERIFIED)
                .build();
    }

    @Test
    void selfUpload에서_REQUEST_LEW로_전환되고_sldFee와_quote가_갱신된다() {
        Application app = newApp(SldOption.SELF_UPLOAD, null, new BigDecimal("650.00"));

        app.switchSldToLewCreated(new BigDecimal("120.00"), new BigDecimal("770.00"));

        assertThat(app.getSldOption()).isEqualTo(SldOption.REQUEST_LEW);
        assertThat(app.getSldFee()).isEqualByComparingTo("120.00");
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("770.00");
    }

    @Test
    void SUBMIT_WITHIN_3_MONTHS에서도_전환_가능() {
        Application app = newApp(SldOption.SUBMIT_WITHIN_3_MONTHS, null, new BigDecimal("650.00"));

        app.switchSldToLewCreated(new BigDecimal("120.00"), new BigDecimal("770.00"));

        assertThat(app.getSldOption()).isEqualTo(SldOption.REQUEST_LEW);
        assertThat(app.getQuoteAmount()).isEqualByComparingTo("770.00");
    }

    @Test
    void 이미_REQUEST_LEW면_전환_거부() {
        Application app = newApp(SldOption.REQUEST_LEW, new BigDecimal("120.00"), new BigDecimal("770.00"));

        assertThatThrownBy(() ->
                app.switchSldToLewCreated(new BigDecimal("120.00"), new BigDecimal("770.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already LEW-created");
    }

    @Test
    void null_인자는_거부() {
        Application app = newApp(SldOption.SELF_UPLOAD, null, new BigDecimal("650.00"));

        assertThatThrownBy(() -> app.switchSldToLewCreated(null, new BigDecimal("770.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> app.switchSldToLewCreated(new BigDecimal("120.00"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
