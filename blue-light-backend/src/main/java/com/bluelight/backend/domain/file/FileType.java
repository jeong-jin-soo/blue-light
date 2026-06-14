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
     * Letter of Appointment — 신청자 서명본(applicant-upload) 및 admin 폼 템플릿에 재사용
     */
    OWNER_AUTH_LETTER,

    /**
     * LEW가 정보 보완해 올린 LoA 최종본 (신청자 서명본과 구분, loa-exchange 재설계 PR3)
     */
    LOA_FINAL,

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
    CIRCUIT_SCHEDULE,

    /**
     * LEW Service 방문 사진 (여러 장 업로드, LEW Service 방문형 리스키닝 PR 3)
     */
    LEW_SERVICE_VISIT_PHOTO,

    /**
     * LEW Service 방문 보고서 PDF (LEW Service 방문형 리스키닝 PR 3)
     */
    LEW_SERVICE_VISIT_REPORT,

    /**
     * Expired License 주문 참고 문서 (신청자가 업로드, 최대 10개/파일당 20MB, 임의 포맷)
     */
    EXPIRED_LICENSE_SUPPORTING_DOC,

    /**
     * Expired License 주문 방문 사진
     */
    EXPIRED_LICENSE_VISIT_PHOTO,

    /**
     * Expired License 주문 방문 보고서 PDF
     */
    EXPIRED_LICENSE_VISIT_REPORT,

    /**
     * EMA ELISE 제출 접수증/확인 스크린샷 (담당 LEW 업로드).
     * {@code system_settings.ema.ack.required=true} 면 제출 계열 전이(T1/T3/T10)의 필수 첨부.
     */
    EMA_ACK
}
