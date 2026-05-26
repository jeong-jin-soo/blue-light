package com.bluelight.backend.domain.notification;

/**
 * 알림 카탈로그 카테고리 — Preference Center 토글·Quiet Hours 정책·lint 가드 분기 기준.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §2(D-6), §8.
 * 카탈로그: {@code doc/Project Analysis/notification-catalog.md} §10.</p>
 *
 * <p>VARCHAR 저장이므로 enum 값 추가는 기존 데이터와 호환된다.</p>
 */
public enum NotificationCategory {
    /** 비밀번호 변경 통보·로그인 알림 등. 옵트아웃 불가, Quiet Hours 예외. */
    SECURITY,
    /** 신청서 상태 전이 (접수, LEW 배정, 결과). */
    STATUS,
    /** 결제 요청·확인·환불. PayNow 가드(H-S2) 적용. */
    PAYMENT,
    /** 리마인더·재촉. 옵트아웃 가능. */
    REMINDER,
    /** Concierge/LEW 현장 방문 일정. */
    VISIT,
    /** 진행 중 안심 메시지. */
    REASSURANCE,
    /** 면허 만료 생애주기 D-90/60/30/7/1. */
    EXPIRY,
    /** 광고성. {@code [ADV]} prefix + opt-out 변수 필수(L3·L4). */
    MARKETING,
    /** 피드백·NPS 요청. */
    FEEDBACK,
    /** 운영/관리자 내부. */
    OPS
}
