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
     * Letter of Appointment
     */
    OWNER_AUTH_LETTER,

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
    LICENSE_PDF,

    /**
     * 결제 영수증
     */
    PAYMENT_RECEIPT,

    /**
     * SP Group 계정 확인 문서
     */
    SP_ACCOUNT_DOC,

    /**
     * 신청자 스케치 (SLD 요청 참고용)
     */
    SKETCH_SLD,

    /**
     * 신청자 스케치 (Lighting Layout 요청 참고용)
     */
    SKETCH_LIGHTING,

    /**
     * 신청자 스케치 (Power Socket 요청 참고용)
     */
    SKETCH_POWER_SOCKET,

    /**
     * 신청자 스케치 (LEW Service 요청 참고용)
     */
    SKETCH_LEW_SERVICE,

    /**
     * 회로 스케줄 파일 (Excel/CSV/이미지 — AI 채팅 첨부용)
     */
    CIRCUIT_SCHEDULE
}
