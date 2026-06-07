package com.bluelight.backend.api.notification.template.lint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 템플릿 변수 추출·화이트리스트 검증 유틸 — Lint L1 (Variable Whitelist) 의 진입점.
 *
 * <p>{@code TemplateRenderer} 의 {@code {{var}}} 패턴과 정확히 동일한 정규식을 사용해야 한다 —
 * "저장 시점 검증" 과 "발송 시점 렌더링" 이 같은 키 정의를 공유해야 하므로.</p>
 *
 * <p><b>JSON 파싱 lenient</b>: variables_json 이 null/빈 문자열이면 빈 셋으로 취급한다 (신규 템플릿
 * 초안 작성 단계에서 변수 풀이 아직 비어있을 수 있음 — 저장 자체는 막지 말고, 본문에 변수가 있으면
 * 그때 ERROR).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TemplateVariableValidator {

    /** {@code {{key}}} — TemplateRenderer 와 동일 패턴. */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    private final ObjectMapper objectMapper;

    /** 본문에서 사용된 모든 {{변수}} 키 추출 (중복 제거, 등장 순서 보존). */
    public Set<String> extractVariables(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = VAR_PATTERN.matcher(text);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** JSON 배열 ({@code ["a","b"]}) → 문자열 셋. 빈/null 입력은 빈 셋. */
    public Set<String> parseVariableSet(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptySet();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            log.warn("variables_json 파싱 실패 — 빈 셋으로 처리: {}", e.getMessage());
            return Collections.emptySet();
        }
    }
}
