package com.bluelight.backend.common.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 파일 암호화/복호화 유틸리티
 *
 * 파일 저장 형식: [IV (12 bytes)] [Ciphertext + GCM Auth Tag (16 bytes)]
 * - IV(Initialization Vector): 파일마다 랜덤 생성, 암호문 앞에 prepend
 * - GCM Auth Tag: 기밀성 + 무결성 동시 보장
 *
 * 키가 설정되지 않으면 암호화 비활성화 (개발환경 호환)
 */
@Slf4j
@Component
public class FileEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;       // 12 bytes (96 bits) — GCM 권장
    private static final int GCM_TAG_LENGTH = 128;      // 128 bits — 최대 인증 태그

    @Value("${file.encryption-key:}")
    private String encryptionKeyBase64;

    private SecretKeySpec secretKey;
    private boolean enabled;

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            enabled = false;
            log.warn("파일 암호화 비활성화: FILE_ENCRYPTION_KEY 미설정");
            return;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "AES-256 키는 32바이트여야 합니다. 현재: " + keyBytes.length + "바이트");
            }
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
            this.enabled = true;
            log.info("파일 암호화 활성화: AES-256-GCM");
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("파일 암호화 키 초기화 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 암호화 활성화 여부
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 바이트 배열을 AES-256-GCM으로 암호화
     *
     * @param plainData 원본 데이터
     * @return [IV(12B) | ciphertext + GCM tag] 형태의 암호문
     */
    public byte[] encrypt(byte[] plainData) {
        if (!enabled) {
            return plainData;
        }

        try {
            // 랜덤 IV 생성
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plainData);

            // [IV | ciphertext] 결합
            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);

            return result;
        } catch (Exception e) {
            throw new RuntimeException("파일 암호화 실패", e);
        }
    }

    /**
     * AES-256-GCM 암호문을 복호화
     *
     * @param encryptedData [IV(12B) | ciphertext + GCM tag] 형태의 암호문
     * @return 복호화된 원본 데이터
     */
    public byte[] decrypt(byte[] encryptedData) {
        if (!enabled) {
            return encryptedData;
        }

        try {
            // IV 추출
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH);

            // 암호문 추출
            int ciphertextLength = encryptedData.length - GCM_IV_LENGTH;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(encryptedData, GCM_IV_LENGTH, ciphertext, 0, ciphertextLength);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("파일 복호화 실패", e);
        }
    }

    /**
     * 데이터가 이 유틸리티로 암호화되었는지 판별
     * GCM 암호문은 최소 IV(12B) + Auth Tag(16B) = 28바이트 이상
     */
    public boolean isLikelyEncrypted(byte[] data) {
        return enabled && data.length >= GCM_IV_LENGTH + 16;
    }
}
