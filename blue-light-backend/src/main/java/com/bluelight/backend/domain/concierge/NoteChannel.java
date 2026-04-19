package com.bluelight.backend.domain.concierge;

/**
 * 컨시어지 연락 기록(ConciergeNote)의 채널 구분 (★ Kaki Concierge v1.5)
 *
 * - PHONE: 전화 통화
 * - EMAIL: 이메일
 * - WHATSAPP: WhatsApp 메시지
 * - IN_PERSON: 대면 미팅
 * - OTHER: 기타
 */
public enum NoteChannel {
    PHONE,
    EMAIL,
    WHATSAPP,
    IN_PERSON,
    OTHER
}
