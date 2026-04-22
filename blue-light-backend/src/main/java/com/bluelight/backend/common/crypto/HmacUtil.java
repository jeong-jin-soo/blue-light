package com.bluelight.backend.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 민감 식별자(특히 MSSL Account No 같이 "검색은 가능해야 하지만 평문 저장은 곤란"한
 * 값)를 결정론적 해시로 변환하기 위한 HMAC-SHA256 유틸리티.
 *
 * <h3>키 파생 방식</h3>
 * {@code FIELD_ENCRYPTION_KEY} 환경변수(=AES-256 암호화 키, Base64)를 재사용하되,
 * HMAC 전용 salt {@code "mssl-hmac-salt-v1"}를 결합하여 HMAC-SHA256 기반의 64바이트
 * 파생키를 만든다. 공식적으로는 HKDF-Extract 한 스텝과 동일하며(prk = HMAC(salt, ikm)),
 * 실질적으로 암호화 키와 HMAC 키를 분리하는 효과가 있다.
 *
 * <h3>출력 포맷</h3>
 * <pre>64자 소문자 hex 문자열 (SHA-256 → 32바이트 → hex 64자)</pre>
 *
 * <h3>키가 비어있을 때</h3>
 * 개발/테스트 환경에서 키를 비워두면 <b>평문 passthrough</b>로 동작한다
 * ({@code hmac(x) = x}). 운영 배포 시에는 반드시 Base64 AES-256 키가 주입되어야 한다.
 * {@link FieldEncryptionUtil}의 정책과 일관되게 유지한다.
 *
 * <p>PDPA §9(①) — MSSL 암호문(앞 12자리) + HMAC 해시(검색 키) + 뒤 4자리 평문
 * 3중 저장 패턴의 "HMAC 해시" 담당.
 */
@Slf4j
@Component
public class HmacUtil {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DERIVATION_SALT = "mssl-hmac-salt-v1";

    @Value("${field.encryption.key:}")
    private String encryptionKeyBase64;

    private SecretKeySpec derivedKey;
    private boolean enabled;

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            this.enabled = false;
            log.warn("HMAC 비활성화 (plain passthrough): FIELD_ENCRYPTION_KEY 미설정");
            return;
        }

        byte[] ikm;
        try {
            ikm = Base64.getDecoder().decode(encryptionKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "FIELD_ENCRYPTION_KEY is not valid Base64", e);
        }
        if (ikm.length != 32) {
            throw new IllegalStateException(
                    "AES-256 키는 32바이트여야 합니다. 현재: " + ikm.length + "바이트");
        }

        // HKDF-Extract 한 스텝: prk = HMAC-SHA256(salt, ikm)
        // salt를 키로, ikm을 메시지로 사용하여 HMAC 전용 파생키를 얻는다.
        try {
            Mac extract = Mac.getInstance(HMAC_ALGORITHM);
            extract.init(new SecretKeySpec(
                    DERIVATION_SALT.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] prk = extract.doFinal(ikm);
            this.derivedKey = new SecretKeySpec(prk, HMAC_ALGORITHM);
            this.enabled = true;
            log.info("HMAC 활성화: HMAC-SHA256 (salt=mssl-hmac-salt-v1)");
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 파생키 초기화 실패", e);
        }
    }

    /** HMAC 활성화 여부 — 테스트 검증용. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 평문을 HMAC-SHA256 해시 hex 문자열로 변환한다.
     *
     * @param plaintext 입력 문자열. null 또는 빈 문자열이면 null 반환.
     * @return 64자 소문자 hex (활성화 상태) 또는 입력 그대로 (passthrough 모드).
     */
    public String hmac(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return null;
        if (!enabled) return plaintext;

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(derivedKey);
            byte[] digest = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }
}
