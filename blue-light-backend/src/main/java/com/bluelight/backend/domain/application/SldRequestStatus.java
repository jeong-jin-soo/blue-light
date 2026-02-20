package com.bluelight.backend.domain.application;

/**
 * SLD 요청 상태
 */
public enum SldRequestStatus {
    REQUESTED,        // 신청자가 LEW에게 SLD 작성 요청
    AI_GENERATING,    // LEW가 AI를 이용해 SLD 생성 중
    UPLOADED,         // LEW가 SLD 파일 업로드 완료
    CONFIRMED         // 확인 완료
}
