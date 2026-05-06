package com.bluelight.backend.domain.application;

import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Application LOA 서명 출처 불변식 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 4).
 * <p>
 * PRD §3.4a / §7.2.1-LOA 3경로 모델 검증.
 * - 최초 1회만 기록
 * - 동일 source 재호출 멱등
 * - 다른 source 덮어쓰기 IllegalStateException
 * - uploader 연결 순서/조건 가드
 */
@DisplayName("Application LOA 서명 출처 - PR#1 Stage 4")
class LoaSignatureSourceRecordTest {

    private User applicant() {
        return User.builder()
            .email("a@b.com").password("h").firstName("A").lastName("B")
            .build();
    }

    private User manager() {
        return User.builder()
            .email("mgr@b.com").password("h").firstName("M").lastName("Gr")
            .build();
    }

    private Application createApp() {
        return Application.builder()
            .user(applicant())
            .address("1 Test Rd")
            .postalCode("111111")
            .selectedKva(10)
            .quoteAmount(new BigDecimal("100.00"))
            .build();
    }

    // ============================================================
    // recordLoaSignatureSource()
    // ============================================================

    @Test
    @DisplayName("recordLoaSignatureSource() - 최초 호출 시 true 반환, 필드 세팅")
    void record_firstCall_recordsAndReturnsTrue() {
        Application app = createApp();

        boolean recorded = app.recordLoaSignatureSource(
            LoaSignatureSource.MANAGER_UPLOAD, 42L, "email receipt");

        assertThat(recorded).isTrue();
        assertThat(app.getLoaSignatureSource()).isEqualTo(LoaSignatureSource.MANAGER_UPLOAD);
        assertThat(app.getLoaSignatureSourceMemo()).isEqualTo("email receipt");
        assertThat(app.getLoaSignatureUploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("recordLoaSignatureSource() - 동일 source 재호출 시 false 반환 (멱등, 타임스탬프 보존)")
    void record_sameSourceAgain_returnsFalse() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.APPLICANT_DIRECT, null, null);
        java.time.LocalDateTime firstAt = app.getLoaSignatureUploadedAt();

        boolean secondCall = app.recordLoaSignatureSource(
            LoaSignatureSource.APPLICANT_DIRECT, null, null);

        assertThat(secondCall).isFalse();
        // 타임스탬프는 최초 값 보존
        assertThat(app.getLoaSignatureUploadedAt()).isEqualTo(firstAt);
    }

    @Test
    @DisplayName("recordLoaSignatureSource() - 다른 source 덮어쓰기 시 IllegalStateException")
    void record_differentSource_throws() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.APPLICANT_DIRECT, null, null);

        assertThatThrownBy(() -> app.recordLoaSignatureSource(
            LoaSignatureSource.MANAGER_UPLOAD, 42L, "memo"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APPLICANT_DIRECT")
            .hasMessageContaining("MANAGER_UPLOAD");
    }

    @Test
    @DisplayName("recordLoaSignatureSource() - source가 null이면 IllegalArgumentException")
    void record_nullSource_throws() {
        Application app = createApp();

        assertThatThrownBy(() -> app.recordLoaSignatureSource(null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ============================================================
    // setLoaSignatureUploadedBy()
    // ============================================================

    @Test
    @DisplayName("setLoaSignatureUploadedBy() - record 선행 없으면 예외")
    void uploader_beforeRecord_throws() {
        Application app = createApp();

        assertThatThrownBy(() -> app.setLoaSignatureUploadedBy(manager()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recordLoaSignatureSource");
    }

    @Test
    @DisplayName("setLoaSignatureUploadedBy() - APPLICANT_DIRECT 상태에서는 업로더 불가")
    void uploader_onDirectSource_throws() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.APPLICANT_DIRECT, null, null);

        assertThatThrownBy(() -> app.setLoaSignatureUploadedBy(manager()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("APPLICANT_DIRECT");
    }

    @Test
    @DisplayName("setLoaSignatureUploadedBy() - MANAGER_UPLOAD 상태에서 정상 세팅")
    void uploader_onManagerUpload_succeeds() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.MANAGER_UPLOAD, 42L, "memo");
        User mgr = manager();

        app.setLoaSignatureUploadedBy(mgr);

        assertThat(app.getLoaSignatureUploadedBy()).isSameAs(mgr);
    }

    @Test
    @DisplayName("setLoaSignatureUploadedBy() - 이중 세팅 방지")
    void uploader_doubleSet_throws() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.MANAGER_UPLOAD, 42L, "memo");
        app.setLoaSignatureUploadedBy(manager());

        assertThatThrownBy(() -> app.setLoaSignatureUploadedBy(manager()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("already set");
    }

    @Test
    @DisplayName("setLoaSignatureUploadedBy() - REMOTE_LINK 경로에서는 정상 세팅 가능")
    void uploader_onRemoteLink_succeeds() {
        Application app = createApp();
        app.recordLoaSignatureSource(LoaSignatureSource.REMOTE_LINK, 42L, "qr sign");
        User issuer = manager();

        app.setLoaSignatureUploadedBy(issuer);

        assertThat(app.getLoaSignatureUploadedBy()).isSameAs(issuer);
    }
}
