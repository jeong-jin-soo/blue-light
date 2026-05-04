package com.bluelight.backend.common.json;

import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

/**
 * JPA {@link AttributeConverter} — {@code List<Long>} 을 JSON 문자열로 직렬화하여 저장하고
 * 역으로 복원한다.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 (PR-2 MULTI 수신자 user seq
 * 목록 컬럼 {@code recipient_user_seqs_json}).</p>
 *
 * <h3>설계 노트</h3>
 * <ul>
 *   <li>Spring 이 JPA Converter 의 생성자 주입을 지원하지 않으므로 자체 ObjectMapper 인스턴스를
 *       static final 로 보유한다 (List<Long> 직렬화에는 추가 모듈이 필요 없음).</li>
 *   <li>{@code @Converter(autoApply=true)} 는 사용하지 않고 필드별로
 *       {@code @Convert(converter = JsonLongListConverter.class)} 를 명시 — 다른 Long 필드에 의도치
 *       않게 JSON 변환이 적용되지 않도록 한다.</li>
 *   <li>null 입력은 null 로, 빈 리스트는 {@code "[]"} 로 변환한다 (DB 검색 시 IS NULL 과 비어있는
 *       리스트를 구분 가능하도록).</li>
 *   <li>역직렬화 실패 시 빈 리스트로 fallback 하고 ERROR 로깅 — 발송 이력 조회가 깨지는 것보다
 *       null-safe 동작이 운영상 유리.</li>
 * </ul>
 */
@Slf4j
public class JsonLongListConverter implements AttributeConverter<List<Long>, String> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<Long>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Long> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (RuntimeException e) {
            // Jackson 의 JacksonException 이 RuntimeException 의 하위라 RuntimeException 으로 흡수.
            log.error("JsonLongListConverter serialize failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    @Override
    public List<Long> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (RuntimeException e) {
            log.error("JsonLongListConverter deserialize failed (dbData={}): {}", dbData, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
