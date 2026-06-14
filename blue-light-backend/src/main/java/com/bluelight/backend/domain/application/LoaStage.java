package com.bluelight.backend.domain.application;

/**
 * LoA(Letter of Appointment) 진행 단계 — 파일 교환 모델(loa-exchange-redesign-spec.md §2.3).
 *
 * <p>기존 디지털 서명 모델(loaSignatureUrl 등)을 대체한다. 플랫폼은 LoA를 생성·서명하지 않고
 * 파일을 주고받는다:
 * <ol>
 *   <li>{@link #NOT_STARTED} — 초기(신청 생성)</li>
 *   <li>{@link #FORM_SENT} — (NEW 전용) LEW가 admin 관리 LoA 폼을 신청자에게 전달</li>
 *   <li>{@link #APPLICANT_UPLOADED} — 신청자가 오프라인 서명본 업로드 (RENEWAL은 여기서 시작 가능)</li>
 *   <li>{@link #FINAL_UPLOADED} — LEW가 정보 보완한 최종본 업로드 완료</li>
 * </ol>
 *
 * <p>게이트: 결제 요청은 {@code APPLICANT_UPLOADED} 이상, PAID→IN_PROGRESS는 {@code FINAL_UPLOADED} 요구.
 */
public enum LoaStage {
    NOT_STARTED,
    FORM_SENT,
    APPLICANT_UPLOADED,
    FINAL_UPLOADED
}
