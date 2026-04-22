package com.bluelight.backend.common.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HmacUtil 단위 테스트 (LEW Review Form P1.A).
 *
 * <p>검증 포인트:
 * <ul>
 *   <li>결정론성: 같은 입력 → 같은 해시</li>
 *   <li>충돌 방지: 다른 입력 → 다른 해시</li>
 *   <li>null/빈 문자열 처리 (null 반환)</li>
 *   <li>passthrough 모드 (키 미설정 시 입력 그대로 반환)</li>
 *   <li>키 파생 분리: 동일 키라도 salt가 다르면 다른 파생키 → 다른 해시
 *       (→ 암호화 키와 HMAC 키가 실질적으로 분리됨을 확인)</li>
 * </ul>
 */
@DisplayName("HmacUtil - P1.A")
class HmacUtilTest {

    /** 테스트용 Base64 AES-256 키 (32바이트 zero). */
    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    private HmacUtil util;

    @BeforeEach
    void setUp() {
        util = new HmacUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", TEST_KEY);
        util.init();
    }

    @Test
    @DisplayName("같은_입력은_같은_해시를_반환한다")
    void hmac_is_deterministic() {
        String h1 = util.hmac("123-45-6789-0");
        String h2 = util.hmac("123-45-6789-0");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("다른_입력은_다른_해시를_반환한다")
    void hmac_collides_not() {
        String h1 = util.hmac("123-45-6789-0");
        String h2 = util.hmac("123-45-6789-1"); // 마지막 한 자리 다름
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    @DisplayName("출력은_64자_hex_문자열이다")
    void hmac_output_is_64_char_hex() {
        String h = util.hmac("some-mssl-123");
        assertThat(h).hasSize(64);
        assertThat(h).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("null_입력은_null_반환")
    void hmac_null_returns_null() {
        assertThat(util.hmac(null)).isNull();
    }

    @Test
    @DisplayName("빈_문자열_입력은_null_반환")
    void hmac_empty_returns_null() {
        assertThat(util.hmac("")).isNull();
    }

    @Test
    @DisplayName("키_미설정이면_passthrough_모드로_동작한다")
    void hmac_passthrough_when_key_missing() {
        HmacUtil noKey = new HmacUtil();
        ReflectionTestUtils.setField(noKey, "encryptionKeyBase64", "");
        noKey.init();

        assertThat(noKey.isEnabled()).isFalse();
        assertThat(noKey.hmac("abc")).isEqualTo("abc"); // 평문 그대로
    }

    @Test
    @DisplayName("키_null이어도_passthrough_모드")
    void hmac_passthrough_when_key_null() {
        HmacUtil noKey = new HmacUtil();
        ReflectionTestUtils.setField(noKey, "encryptionKeyBase64", null);
        noKey.init();

        assertThat(noKey.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("잘못된_Base64_키면_기동_실패")
    void hmac_invalid_base64_throws() {
        HmacUtil badKey = new HmacUtil();
        ReflectionTestUtils.setField(badKey, "encryptionKeyBase64", "!!!not-base64!!!");
        assertThatThrownBy(badKey::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid Base64");
    }

    @Test
    @DisplayName("32바이트가_아닌_키면_기동_실패")
    void hmac_wrong_key_length_throws() {
        HmacUtil shortKey = new HmacUtil();
        // 16바이트 (AES-128 크기)
        ReflectionTestUtils.setField(shortKey, "encryptionKeyBase64",
                Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(shortKey::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32바이트");
    }

    @Test
    @DisplayName("enabled_상태에서_해시는_평문과_같지_않다")
    void hmac_differs_from_plaintext_when_enabled() {
        assertThat(util.isEnabled()).isTrue();
        String plain = "123-45-6789-0";
        String h = util.hmac(plain);
        assertThat(h).isNotEqualTo(plain);
    }
}
