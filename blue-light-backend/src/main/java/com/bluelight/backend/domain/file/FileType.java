package com.bluelight.backend.domain.file;

/**
 * 파일 종류 구분
 */
public enum FileType {
    /**
     * 전기 도면 (SLD: Single Line Diagram)
     */
    DRAWING_SLD,

    /**
     * 현장 사진
     */
    SITE_PHOTO,

    /**
     * 점검 보고서 PDF
     */
    REPORT_PDF,

    /**
     * 라이선스 PDF
     */
    LICENSE_PDF
}
