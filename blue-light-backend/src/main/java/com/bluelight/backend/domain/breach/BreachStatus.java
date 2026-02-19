package com.bluelight.backend.domain.breach;

/**
 * 데이터 유출 통보 상태
 */
public enum BreachStatus {
    DETECTED,           // 유출 감지
    INVESTIGATING,      // 조사 중
    PDPC_NOTIFIED,      // PDPC 통보 완료
    USERS_NOTIFIED,     // 영향 받은 사용자 통보 완료
    CONTAINED,          // 유출 차단 완료
    RESOLVED            // 해결 완료
}
