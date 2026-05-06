package com.bluelight.backend.common.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 PR#1 — B-4 prod 프로필에서 FILE_ENCRYPTION_KEY 미설정 시 기동 실패
 */
class FileEncryptionUtilTest {

    @Test
    void prod_프로필에서_키가_없으면_기동_실패() {
        FileEncryptionUtil util = new FileEncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", "");
        ReflectionTestUtils.setField(util, "activeProfile", "prod");
        assertThatThrownBy(util::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILE_ENCRYPTION_KEY is required in prod profile");
    }

    @Test
    void prod_프로필에서_키가_null이어도_기동_실패() {
        FileEncryptionUtil util = new FileEncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", null);
        ReflectionTestUtils.setField(util, "activeProfile", "prod");
        assertThatThrownBy(util::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prod_콤마_혼합_프로필에서도_감지() {
        FileEncryptionUtil util = new FileEncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", "");
        ReflectionTestUtils.setField(util, "activeProfile", "aws,prod,monitoring");
        assertThatThrownBy(util::init).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dev_default_프로필에서_키_없으면_경고만하고_비활성화() {
        FileEncryptionUtil util = new FileEncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", "");
        ReflectionTestUtils.setField(util, "activeProfile", "default");
        util.init(); // 예외 없음
        assertThat(util.isEnabled()).isFalse();
    }

    @Test
    void prod_프로필에서_키가_있으면_정상_초기화() {
        // Base64-encoded 32-byte key
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        String base64 = java.util.Base64.getEncoder().encodeToString(key);

        FileEncryptionUtil util = new FileEncryptionUtil();
        ReflectionTestUtils.setField(util, "encryptionKeyBase64", base64);
        ReflectionTestUtils.setField(util, "activeProfile", "prod");
        util.init();
        assertThat(util.isEnabled()).isTrue();

        byte[] roundTrip = util.decrypt(util.encrypt("hello".getBytes()));
        assertThat(new String(roundTrip)).isEqualTo("hello");
    }

    @Test
    void isProdProfile_헬퍼_검증() {
        assertThat(FileEncryptionUtil.isProdProfile(null)).isFalse();
        assertThat(FileEncryptionUtil.isProdProfile("")).isFalse();
        assertThat(FileEncryptionUtil.isProdProfile("prod")).isTrue();
        assertThat(FileEncryptionUtil.isProdProfile("PROD")).isTrue();
        assertThat(FileEncryptionUtil.isProdProfile("dev,prod")).isTrue();
        assertThat(FileEncryptionUtil.isProdProfile("dev")).isFalse();
        assertThat(FileEncryptionUtil.isProdProfile("production")).isFalse();
    }
}
