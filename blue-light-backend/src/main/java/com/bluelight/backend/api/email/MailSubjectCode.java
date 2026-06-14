package com.bluelight.backend.api.email;

/**
 * 메일 제목에 붙이는 "메일 코드"(알림 카탈로그 코드, 예: {@code A-17}) prefix 유틸.
 *
 * <p>운영(prod 프로필)이 아닌 환경(개발서버 등)에서는 발송 메일 제목 앞에 {@code "[코드] "} 를 붙여
 * 어떤 알림인지 식별하기 쉽게 한다. 운영에서는 제목에 변화가 없다.</p>
 *
 * <p>운영 서버만 {@code SPRING_PROFILES_ACTIVE=prod} 로 뜨므로 "prod 가 아니면 비운영" 으로 판별한다.
 * ({@code FileEncryptionUtil.isProdProfile} 과 동일한 규칙)</p>
 */
public final class MailSubjectCode {

    private MailSubjectCode() {}

    /**
     * 비운영 환경에서 {@code "[code] "} prefix 를, 운영이거나 코드가 없으면 빈 문자열을 반환한다.
     *
     * @param activeProfiles {@code spring.profiles.active} 값(콤마 다중 프로필 허용)
     * @param code           메일 코드(예: {@code "A-17"}). null/blank 면 prefix 없음.
     */
    public static String prefix(String activeProfiles, String code) {
        if (code == null || code.isBlank() || isProdProfile(activeProfiles)) {
            return "";
        }
        return "[" + code + "] ";
    }

    /**
     * {@code spring.profiles.active} 에 "prod" 가 포함되어 있는지 검사(콤마 다중 프로필 지원).
     */
    public static boolean isProdProfile(String activeProfiles) {
        if (activeProfiles == null || activeProfiles.isBlank()) return false;
        for (String token : activeProfiles.split(",")) {
            if ("prod".equalsIgnoreCase(token.trim())) return true;
        }
        return false;
    }
}
