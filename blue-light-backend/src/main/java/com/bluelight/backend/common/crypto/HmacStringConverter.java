package com.bluelight.backend.common.crypto;

import com.bluelight.backend.common.ApplicationContextHolder;
import jakarta.persistence.AttributeConverter;

/**
 * JPA `AttributeConverter` — 엔티티 속성을 HMAC-SHA256 해시로 변환하여 DB에 저장한다.
 *
 * <p>단방향 컨버터: {@code convertToDatabaseColumn}에서만 해싱을 수행하며,
 * {@code convertToEntityAttribute}는 이미 해시된 hex 문자열을 그대로 반환한다
 * (복호화 불가능).</p>
 *
 * <p>사용법: {@code @Convert(converter = HmacStringConverter.class)}를 필요한 필드에만
 * 명시적으로 선언한다. 대상: 검색이 가능해야 하면서 평문 저장이 곤란한 식별자
 * (예: MSSL Account No의 검색 키).</p>
 *
 * <p>엔티티에 평문을 할당하고 persist/merge하면, DB에는 HMAC 해시가 저장된다.
 * 이후 동일 평문을 할당하면 동일 해시가 나오므로 equality 검색에 사용할 수 있다.</p>
 *
 * <p>{@link EncryptedStringConverter}와 동일한 패턴으로 Spring 컨텍스트가 아직
 * 초기화되지 않은 상태(단위 테스트 등)에서는 passthrough로 동작한다.</p>
 */
public class HmacStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        HmacUtil util = resolve();
        return util == null ? attribute : util.hmac(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        // 이미 해시된 hex 문자열 — 역변환 불가능, 그대로 반환.
        return dbData;
    }

    private HmacUtil resolve() {
        try {
            return ApplicationContextHolder.getBean(HmacUtil.class);
        } catch (Exception ignored) {
            // 컨텍스트 미초기화 / 빈 미등록 → passthrough
            return null;
        }
    }
}
