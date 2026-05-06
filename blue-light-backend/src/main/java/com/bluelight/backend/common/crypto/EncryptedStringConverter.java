package com.bluelight.backend.common.crypto;

import com.bluelight.backend.common.ApplicationContextHolder;
import jakarta.persistence.AttributeConverter;

/**
 * JPA `AttributeConverter` — 엔티티 속성 수준에서 문자열을 AES-256-GCM으로
 * 암호화하여 DB에 저장하고, 읽을 때 복호화한다.
 *
 * <p>`@Converter(autoApply=true)` 는 사용하지 <b>않는다</b> — 필요한 필드에만
 * `@Convert(converter = EncryptedStringConverter.class)` 를 명시적으로 선언한다.
 * 대상: EMA ELISE 개인정보 (Landlord EI licence 번호, correspondence address 등).</p>
 *
 * <p>Spring 이 JPA Converter 의 생성자 주입을 지원하지 않으므로, 런타임에
 * {@link ApplicationContextHolder} 를 통해 {@link FieldEncryptionUtil} 을 조회한다.
 * 컨텍스트가 아직 로드되지 않은 상태(단위 테스트 등)에서는 passthrough로 동작한다.</p>
 */
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        FieldEncryptionUtil util = resolve();
        return util == null ? attribute : util.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        FieldEncryptionUtil util = resolve();
        return util == null ? dbData : util.decrypt(dbData);
    }

    private FieldEncryptionUtil resolve() {
        try {
            return ApplicationContextHolder.getBean(FieldEncryptionUtil.class);
        } catch (Exception ignored) {
            // 컨텍스트 미초기화 / 빈 미등록 → passthrough
            return null;
        }
    }
}
