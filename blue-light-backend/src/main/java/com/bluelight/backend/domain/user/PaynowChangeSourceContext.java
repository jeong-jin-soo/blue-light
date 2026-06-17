package com.bluelight.backend.domain.user;

/**
 * PayNow 값 변경이 발생한 맥락 ({@link LewPaynowChangeLog} 용).
 * <p>
 * 변경 이력의 출처를 시계열로 구분해 감사 추적성을 확보한다(D-PN3 변경이력 필수).
 *
 * - ACCOUNT_SETUP: 초대 셋업 화면에서 최초 입력
 * - SIGNUP: 자가가입(SignupPage, role=LEW) 시 최초 입력
 * - PROFILE_UPDATE: 가입 후 본인 프로필에서 변경
 */
public enum PaynowChangeSourceContext {
    ACCOUNT_SETUP,
    SIGNUP,
    PROFILE_UPDATE
}
