package com.bluelight.backend.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * DB 컬럼 단위(필드) 암호화 전용 AES-256-GCM 유틸리티.
 *
 * <p>파일 스토리지 암호화용 {@link com.bluelight.backend.common.util.FileEncryptionUtil}
 * 과 키를 분리한다 (키 분리 원칙 — 스코프 누출 방지).</p>
 *
 * <h3>출력 포맷</h3>
 * <pre>v1:BASE64(IV(12) || ciphertext || tag(16))</pre>
 * — 버전 프리픽스가 있어 추후 알고리즘 교체 시 롤링 복호화가 가능하다.
 *
 * <h3>키가 비어있을 때</h3>
 * 개발/테스트 환경에서 키를 비워두면 <b>평문 패스스루</b> 모드로 동작한다
 * (encrypt/decrypt 모두 원문을 그대로 반환). 운영 배포 시에는 반드시 Base64
 * AES-256 키를 `FIELD_ENCRYPTION_KEY` 환경변수로 주입해야 한다.
 */
@Slf4j
@Component
public class FieldEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;        // 12 bytes (96 bits) — GCM 권장
    private static final int GCM_TAG_LENGTH_BITS = 128; // 128 bits — 최대 인증 태그
    private static final String VERSION_PREFIX = "v1:";

    @Value("${field.encryption.key:}")
    private String encryptionKeyBase64;

    private SecretKeySpec secretKey;
    private boolean enabled;

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            this.enabled = false;
            log.warn("필드 암호화 비활성화 (plain passthrough): FIELD_ENCRYPTION_KEY 미설정");
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "FIELD_ENCRYPTION_KEY is not valid Base64", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "AES-256 키는 32바이트여야 합니다. 현재: " + keyBytes.length + "바이트");
        }
        this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        this.enabled = true;
        log.info("필드 암호화 활성화: AES-256-GCM (format v1)");
    }

    /** 암호화 활성화 여부 — 테스트에서 검증용. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 평문 문자열을 암호화하여 `v1:...` 포맷 문자열로 반환한다.
     * 입력이 null 이면 null 을 반환한다.
     * 암호화 비활성 상태에서는 입력을 그대로 반환한다 (passthrough).
     */
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        if (!enabled) return plainText;

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] cipherAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[GCM_IV_LENGTH + cipherAndTag.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherAndTag, 0, combined, GCM_IV_LENGTH, cipherAndTag.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("필드 암호화 실패", e);
        }
    }

    /**
     * `v1:...` 포맷의 암호문을 복호화한다. 입력이 null 이면 null 을 반환한다.
     * passthrough 모드에서는 입력을 그대로 반환한다 (legacy 평문 호환).
     * 접두사 없는 값이 들어오면 평문으로 간주하여 그대로 반환 — 점진적 마이그레이션 허용.
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        if (!enabled) return ciphertext;
        if (!ciphertext.startsWith(VERSION_PREFIX)) {
            // 접두사 없음 → 아직 암호화되지 않은 legacy 값으로 간주
            return ciphertext;
        }

        try {
            byte[] combined = Base64.getDecoder()
                    .decode(ciphertext.substring(VERSION_PREFIX.length()));
            if (combined.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("ciphertext too short");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] cipherAndTag = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherAndTag, 0, cipherAndTag.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            return new String(cipher.doFinal(cipherAndTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("필드 복호화 실패", e);
        }
    }
}
