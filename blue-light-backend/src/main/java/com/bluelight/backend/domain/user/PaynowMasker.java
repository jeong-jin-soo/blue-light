package com.bluelight.backend.domain.user;

/**
 * LEW PayNow 값 마스킹 — 목록/상세 등 정산 권한자 외 노출을 줄이기 위한 기본 표시(D-PN5).
 * <p>
 * 마지막 4자만 남기고 앞을 {@code *} 로 가린다 (예: 97771983 → ****1983, 201837490N → ******490N).
 * 실제 전체값은 reveal 엔드포인트(열람 감사 기록)로만 노출한다.
 */
public final class PaynowMasker {

    private PaynowMasker() {
    }

    /** null/blank 이면 null 반환. 4자 이하이면 전부 마스킹. */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        int keep = 4;
        if (v.length() <= keep) {
            return "*".repeat(v.length());
        }
        return "*".repeat(v.length() - keep) + v.substring(v.length() - keep);
    }
}
