package com.bluelight.backend.domain.payment;

/**
 * Payment가 가리키는 엔티티 유형 (★ Kaki Concierge v1.5 Phase 1 PR#7, PRD §3.8).
 * <p>
 * Payment는 여러 도메인의 결제를 다형적으로 기록한다:
 * <ul>
 *   <li>APPLICATION: 라이선스 신청 수수료 (기존 데이터 — Phase 1 이전의 모든 결제)</li>
 *   <li>CONCIERGE_REQUEST: Concierge 서비스 요금 (Phase 2부터 실제 사용)</li>
 *   <li>SLD_ORDER: SLD 주문 결제 — 현재는 SldOrderPayment 별도 엔티티이지만
 *       향후 Payment로 통합될 경우에 대비해 enum 값만 선점</li>
 * </ul>
 * <p>
 * 권한 분기(§8.4b): {@code referenceType}에 따라 소유권 검증 로직이 달라진다.
 */
public enum PaymentReferenceType {
    APPLICATION,
    CONCIERGE_REQUEST,
    SLD_ORDER
}
