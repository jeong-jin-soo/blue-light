package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * History 적재용 스냅샷·diff 직렬화 유틸.
 *
 * <p>{@link #snapshot(NotificationTemplate)} — 전체 row → JSON Map (롤백용).
 * {@link #diff(Map, Map)} — 변경된 필드만 추출한 압축 diff (UI 표시용).</p>
 */
@Component
@RequiredArgsConstructor
public class TemplateSnapshotMapper {

    private final ObjectMapper objectMapper;

    /** 빈 스냅샷 — CREATE 시점의 before 로 사용. */
    public String emptySnapshotJson() {
        return "{}";
    }

    public Map<String, Object> snapshot(NotificationTemplate template) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("templateCode", template.getTemplateCode());
        snap.put("channel", template.getChannel().name());
        snap.put("locale", template.getLocale());
        snap.put("subject", template.getSubject());
        snap.put("bodyText", template.getBodyText());
        snap.put("variablesJson", template.getVariablesJson());
        snap.put("providerTemplateName", template.getProviderTemplateName());
        snap.put("enabled", template.isEnabled());
        snap.put("category", template.getCategory() != null ? template.getCategory().name() : null);
        snap.put("severity", template.getSeverity() != null ? template.getSeverity().name() : null);
        snap.put("recipientRoles", template.getRecipientRoles());
        snap.put("catalogMetaKey", template.getCatalogMetaKey());
        return snap;
    }

    public Map<String, Object> snapshot(NotificationTemplateDraft draft) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("templateCode", draft.getTemplateCode());
        snap.put("channel", draft.getChannel().name());
        snap.put("locale", draft.getLocale());
        snap.put("subject", draft.getSubject());
        snap.put("bodyText", draft.getBodyText());
        snap.put("variablesJson", draft.getVariablesJson());
        snap.put("providerTemplateName", draft.getProviderTemplateName());
        snap.put("category", draft.getCategory() != null ? draft.getCategory().name() : null);
        snap.put("severity", draft.getSeverity() != null ? draft.getSeverity().name() : null);
        snap.put("recipientRoles", draft.getRecipientRoles());
        return snap;
    }

    /** 변경된 필드만 추출 — {@code {field: {before:..., after:...}}}. */
    public Map<String, Map<String, Object>> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Map<String, Object>> diff = new LinkedHashMap<>();
        for (String key : after.keySet()) {
            Object beforeVal = before.get(key);
            Object afterVal = after.get(key);
            if (!Objects.equals(beforeVal, afterVal)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", beforeVal);
                change.put("after", afterVal);
                diff.put(key, change);
            }
        }
        return diff;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 직렬화 실패", e);
        }
    }
}
