package com.bluelight.backend.api.admin.notification.template.dto;

/**
 * PR-T7 P1 — 외주 번역 라운드용 파일 포맷.
 *
 * <p>XLIFF 1.2 는 LSP(Localization Service Provider) 표준으로 가장 널리 지원.
 * CSV 는 스프레드시트 도구로 가벼운 편집 가능. 둘 다 trans-unit 단위는 (code, channel, field) 키.</p>
 */
public enum LocalizationFormat {
    XLIFF("application/xliff+xml", "xliff"),
    CSV("text/csv", "csv");

    private final String mediaType;
    private final String extension;

    LocalizationFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() { return mediaType; }
    public String extension() { return extension; }

    public static LocalizationFormat fromString(String s) {
        if (s == null) return XLIFF;
        try {
            return LocalizationFormat.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported format: " + s + " (allowed: xliff, csv)");
        }
    }
}
