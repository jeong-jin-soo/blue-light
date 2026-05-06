package com.bluelight.backend.domain.application;

/**
 * SLD(Single Line Diagram) 제출 방식
 *
 * - SELF_UPLOAD: 신청자가 SLD 파일을 직접 업로드한다.
 * - SUBMIT_WITHIN_3_MONTHS: 일단 신청을 진행하고 EMA ELISE 규정상 허용되는
 *   3개월 이내에 SLD를 제출한다 (JIT 원칙 — 신청 시점에 SLD가 준비되지 않아도 진행).
 * - REQUEST_LEW: LEW에게 SLD 작성을 유료로 의뢰한다.
 *
 * 순서는 신청자 UX 기본 추천 순서(가장 단순한 경로 먼저)를 따른다.
 */
public enum SldOption {
    SELF_UPLOAD,              // 신청자가 직접 업로드
    SUBMIT_WITHIN_3_MONTHS,   // EMA 허용 3개월 유예 — 추후 제출
    REQUEST_LEW               // LEW에게 작성 요청
}
