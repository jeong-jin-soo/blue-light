package com.bluelight.backend.domain.notification.whatsapp;

/**
 * WhatsApp 메시지를 발송한 외부 provider.
 *
 * <p>D1 결정 (2026-05-11): BSP 없이 Meta WhatsApp Cloud API 를 직접 호출한다.
 * 향후 BSP (Wati, 360dialog, Twilio 등) 전환 필요 시 enum 값을 추가하고
 * {@code WhatsappClient} 구현체만 갈아끼우면 된다.</p>
 */
public enum WhatsappProvider {
    /** Meta WhatsApp Cloud API (Graph API) 직접 호출. */
    META,
    /** 로컬·CI 테스트용 mock — 외부 호출 없이 stdout 로그만 남긴다. */
    MOCK
}
