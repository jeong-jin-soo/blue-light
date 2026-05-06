package com.bluelight.backend.domain.application;

/**
 * LOA 서명 출처 분류 (★ Kaki Concierge v1.5, PRD §3.4a / §7.2.1-LOA 3경로 모델)
 *
 * <ul>
 *   <li>{@link #APPLICANT_DIRECT}: 신청자가 로그인 후 직접 서명 (기존 경로, 기본값)</li>
 *   <li>{@link #MANAGER_UPLOAD}: Concierge Manager가 신청자로부터 수령한 서명 파일을 대리 업로드 (경로 A, Phase 1 PR#6)</li>
 *   <li>{@link #REMOTE_LINK}: 일회성 토큰 링크/QR을 통한 비로그인 원격 서명 (경로 B, Phase 2)</li>
 * </ul>
 *
 * 법적 무결성 등급은 PRD §7.2.1-LOA 표 참조 (경로 B가 가장 약함, 경로 A는 Manager 책임 하).
 */
public enum LoaSignatureSource {
    APPLICANT_DIRECT,
    MANAGER_UPLOAD,
    REMOTE_LINK
}
