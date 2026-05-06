package com.bluelight.backend.domain.user;

/**
 * 동의 행위 구분 (★ Kaki Concierge v1.3, PRD §3.11)
 *
 * - GRANTED: 동의 부여
 * - WITHDRAWN: 동의 철회
 */
public enum ConsentAction {
    GRANTED,
    WITHDRAWN
}
