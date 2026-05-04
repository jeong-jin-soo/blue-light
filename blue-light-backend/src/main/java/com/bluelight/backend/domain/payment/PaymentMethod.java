package com.bluelight.backend.domain.payment;

/**
 * 결제 수단 분류 (★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-1, D2=B).
 * <p>
 * 온라인 PG와 ADMIN 수기 기록(offline)을 구분한다.
 * <ul>
 *   <li>{@link #PAYNOW_ONLINE} — 신청자가 PayNow 온라인 결제로 정산 (기본값, 기존 흐름)</li>
 *   <li>{@link #BANK_TRANSFER} — 은행 송금으로 정산 (offline)</li>
 *   <li>{@link #PAYNOW_OFFLINE} — PayNow QR/송금 등 비온라인 PG 정산 (offline)</li>
 *   <li>{@link #CASH} — 현금 (offline)</li>
 *   <li>{@link #OTHER} — 그 외 (offline)</li>
 * </ul>
 * <p>
 * "Offline" 4종은 ADMIN이 PG를 거치지 않은 정산을 수기로 기록할 때 사용된다.
 * 도메인 분류이므로 admin 설정(master_prices, system_settings)에 보관할 대상이 아니다.
 * UI 라벨/배지는 본 enum 키를 i18n 메시지로 매핑한다 (하드코딩 금지).
 */
public enum PaymentMethod {
    PAYNOW_ONLINE,
    BANK_TRANSFER,
    PAYNOW_OFFLINE,
    CASH,
    OTHER;

    /**
     * Offline 결제 수단 여부 (ADMIN 수기 기록 대상).
     */
    public boolean isOffline() {
        return this != PAYNOW_ONLINE;
    }
}
