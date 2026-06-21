package com.bluelight.backend.domain.application;

/**
 * LoA(Letter of Appointment) 진행 단계 — <b>LEW 최종본 트랙</b>만 표현한다.
 *
 * <p>신청자 LoA(자발 첨부 또는 LEW가 Documents 서류요청으로 받은 서명본)는 이 단계와 무관하게
 * <b>Documents 영역의 {@code OWNER_AUTH_LETTER} 파일 + DocumentRequest 상태</b>로만 추적된다.
 * 즉 <b>신청자가 올리는 LoA</b>와 <b>LEW가 올리는 최종 LoA</b>는 명확히 구분되며, 이 enum 은
 * 후자(LEW 최종본)의 존재 여부만 나타낸다.</p>
 *
 * <ol>
 *   <li>{@link #NOT_STARTED} — LEW 최종본 미업로드(초기)</li>
 *   <li>{@link #FINAL_UPLOADED} — LEW가 보완한 최종본 업로드 완료</li>
 * </ol>
 *
 * <p>게이트: PAID→IN_PROGRESS 는 {@code FINAL_UPLOADED} 를 요구한다(작업개시 게이트).
 * 결제 요청은 kVA 확정만으로 가능(LoA 게이트 없음).</p>
 */
public enum LoaStage {
    NOT_STARTED,
    FINAL_UPLOADED
}
