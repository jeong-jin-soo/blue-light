package com.bluelight.backend.domain.application;

/**
 * 신청자 유형: 개인(INDIVIDUAL) / 법인(CORPORATE)
 *
 * Phase 1에서는 플래그만 저장하며, 법인일 경우 회사정보 JIT 요청은
 * Phase 2에서 처리한다 (01-spec.md AC-A5 참조).
 */
public enum ApplicantType {
    INDIVIDUAL,
    CORPORATE
}
