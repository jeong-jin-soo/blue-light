package com.bluelight.backend.common.json;

import jakarta.persistence.AttributeConverter;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

/**
 * JPA {@link AttributeConverter} — {@code List<String>} 을 JSON 문자열로 직렬화하여 저장하고
 * 역으로 복원한다.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §4 (PR-2 MULTI 수신자 이메일
 * 목록 컬럼 {@code recipient_emails_json}).</p>
 *
 * <p>설계 노트는 {@link JsonLongListConverter} 와 동일.</p>
 */
@Slf4j
public class JsonStringListConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (RuntimeException e) {
            log.error("JsonStringListConverter serialize failed: {}", e.getMessage(), e);
            return "[]";
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, TYPE);
        } catch (RuntimeException e) {
            log.error("JsonStringListConverter deserialize failed (dbData={}): {}", dbData, e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
