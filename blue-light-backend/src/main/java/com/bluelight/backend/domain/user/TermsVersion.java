package com.bluelight.backend.domain.user;

/**
 * 약관 버전 상수 (★ Kaki Concierge v1.5, O-19 반영)
 * <p>
 * Phase 1~2에서는 Java 상수로 관리하며, Phase 3에서 {@code terms_documents} DB CMS로 전환 예정.
 * 동의 스냅샷 시점의 버전을 {@code user_consent_logs.version}과
 * {@code users.terms_version} 컬럼에 기록하여 감사 추적성 확보.
 */
public final class TermsVersion {

    /**
     * 현재 적용 중인 약관 버전 (YYYY-MM-DD 포맷)
     */
    public static final String CURRENT = "2026-04-19";

    private TermsVersion() {
        // utility class
    }
}
