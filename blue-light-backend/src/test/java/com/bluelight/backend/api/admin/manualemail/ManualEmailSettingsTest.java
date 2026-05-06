package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-4 — {@link ManualEmailSettings} 단위 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §13.3 + 설계 원칙 "설정 우선".</p>
 */
@DisplayName("ManualEmailSettings — PR-4")
class ManualEmailSettingsTest {

    private SystemSettingRepository repo;
    private ManualEmailSettings settings;

    @BeforeEach
    void setUp() {
        repo = mock(SystemSettingRepository.class);
        settings = new ManualEmailSettings(repo);
    }

    @Test
    @DisplayName("daily cap row 존재 — 그대로 파싱")
    void dailyCap_normal() {
        when(repo.findById(ManualEmailSettings.KEY_DAILY_CAP))
                .thenReturn(Optional.of(new SystemSetting(ManualEmailSettings.KEY_DAILY_CAP, "200", "")));

        assertThat(settings.loadDailyCap()).isEqualTo(200);
    }

    @Test
    @DisplayName("daily cap row 미존재 — 기본값 100 폴백")
    void dailyCap_default() {
        when(repo.findById(ManualEmailSettings.KEY_DAILY_CAP)).thenReturn(Optional.empty());

        assertThat(settings.loadDailyCap()).isEqualTo(ManualEmailSettings.DEFAULT_DAILY_CAP);
    }

    @Test
    @DisplayName("daily cap 파싱 실패 — 기본값 100 폴백 (graceful)")
    void dailyCap_parseFail_default() {
        when(repo.findById(ManualEmailSettings.KEY_DAILY_CAP))
                .thenReturn(Optional.of(new SystemSetting(ManualEmailSettings.KEY_DAILY_CAP, "abc", "")));

        assertThat(settings.loadDailyCap()).isEqualTo(ManualEmailSettings.DEFAULT_DAILY_CAP);
    }

    @Test
    @DisplayName("daily cap 음수/0 — 기본값 100 폴백 (안전망)")
    void dailyCap_zeroOrNegative_default() {
        when(repo.findById(ManualEmailSettings.KEY_DAILY_CAP))
                .thenReturn(Optional.of(new SystemSetting(ManualEmailSettings.KEY_DAILY_CAP, "0", "")));

        assertThat(settings.loadDailyCap()).isEqualTo(ManualEmailSettings.DEFAULT_DAILY_CAP);
    }

    @Test
    @DisplayName("category suggestions row 존재 — CSV 파싱 + trim + 중복 제거")
    void categorySuggestions_normal() {
        when(repo.findById(ManualEmailSettings.KEY_CATEGORY_SUGGESTIONS))
                .thenReturn(Optional.of(new SystemSetting(
                        ManualEmailSettings.KEY_CATEGORY_SUGGESTIONS,
                        " A , B,C , A ,",  // 중복 + 공백 + 후행 콤마
                        "")));

        assertThat(settings.loadCategorySuggestions()).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("category suggestions row 미존재 — 기본 4개 폴백")
    void categorySuggestions_default() {
        when(repo.findById(ManualEmailSettings.KEY_CATEGORY_SUGGESTIONS)).thenReturn(Optional.empty());

        assertThat(settings.loadCategorySuggestions())
                .containsExactly("PAYMENT_NOTICE", "MAINTENANCE", "INFO", "MISC");
    }

    @Test
    @DisplayName("category suggestions 빈 문자열 — 기본 4개 폴백")
    void categorySuggestions_blank_default() {
        when(repo.findById(ManualEmailSettings.KEY_CATEGORY_SUGGESTIONS))
                .thenReturn(Optional.of(new SystemSetting(
                        ManualEmailSettings.KEY_CATEGORY_SUGGESTIONS, "  ", "")));

        assertThat(settings.loadCategorySuggestions())
                .containsExactly("PAYMENT_NOTICE", "MAINTENANCE", "INFO", "MISC");
    }
}
