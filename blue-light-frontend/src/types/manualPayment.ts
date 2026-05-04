/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-4 — Manual Payment 타입.
 *
 * <p>스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §7.</p>
 *
 * <p>백엔드 mirror:</p>
 * <ul>
 *   <li>{@code PaymentMethod} enum — domain.payment.PaymentMethod</li>
 *   <li>{@code ManualPaymentRequest/Response} — api.admin.dto</li>
 *   <li>{@code ConciergeManualPaymentRequest} — api.concierge.dto</li>
 * </ul>
 *
 * <p>설정 우선 원칙 예외: PaymentMethod 라벨/순서는 도메인 분류로 코드 고정 (스펙 §7.2 명시).
 * UI 라벨은 i18n 메시지 또는 상수 매핑으로 제공 — 본 파일은 enum 키만 정의.
 */

/**
 * 결제 수단 enum (백엔드 PaymentMethod mirror).
 *
 * <p>{@code PAYNOW_ONLINE} 은 사이트 내 PG 결제 (기존 confirmPayment 흐름) — 수동 입력에서는 거부됨.
 * 그 외 4종이 offline manual payment 에서 선택 가능.</p>
 */
export type PaymentMethod =
  | 'PAYNOW_ONLINE'
  | 'BANK_TRANSFER'
  | 'PAYNOW_OFFLINE'
  | 'CASH'
  | 'OTHER';

/**
 * Offline 결제 수단 4종 — manual payment 모달에서 선택 가능한 옵션.
 *
 * 설정 우선 원칙 예외: 회계상 결제 분류는 코드 고정 (스펙 §7.2).
 * UI 라벨은 본 객체에서 매핑한다. system_settings 의 payment_method_labels_json 은 PR-5
 * 이후 도입 — 현재 단계는 enum 키 → 영문 라벨 직매핑으로 충분.
 */
export const OFFLINE_PAYMENT_METHODS: PaymentMethod[] = [
  'BANK_TRANSFER',
  'PAYNOW_OFFLINE',
  'CASH',
  'OTHER',
];

/**
 * UI 표시용 라벨 (영문). PaymentMethod enum 자체는 도메인 고정,
 * 라벨은 별도 매핑으로 분리 (i18n 대비).
 */
export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  PAYNOW_ONLINE: 'PayNow (online)',
  BANK_TRANSFER: 'Bank transfer',
  PAYNOW_OFFLINE: 'PayNow (offline)',
  CASH: 'Cash',
  OTHER: 'Other',
};

/**
 * Manual payment 입력 요청 (Application + ConciergeRequest 공용 시그니처).
 *
 * <p>백엔드 ManualPaymentRequest / ConciergeManualPaymentRequest 와 동일한 필드.</p>
 */
export interface ManualPaymentPayload {
  /** SGD 금액 (양수) */
  amount: number;
  /** 결제일 (LocalDate ISO 문자열, e.g. "2026-05-01") */
  paidAt: string;
  /** 결제 수단 (offline 4종 — PAYNOW_ONLINE 은 백엔드에서 거부) */
  paymentMethod: PaymentMethod;
  /** 송금 참조번호 등 메모 (max 500) */
  referenceNote?: string;
  /** 영수증 자동 발행 여부 — 미지정 시 백엔드 기본값 true */
  receiptIssue?: boolean;
}

/**
 * Manual payment 응답 (백엔드 ManualPaymentResponse mirror).
 *
 * <p>Invoice 발행 결과는 AFTER_COMMIT 비동기 — invoiceSeq 는 서버 동기 발행 시에만 채워질 수 있다.
 * 프론트는 인앱 알림 / invoice 후속 GET 로 발행 결과를 인지한다.</p>
 */
export interface ManualPaymentResponse {
  paymentSeq: number;
  amount: number;
  paymentMethod: PaymentMethod;
  paidAt: string;
  recordedAt: string;
  receiptIssued: boolean;
  /** AFTER_COMMIT 동기 발행 시 채워짐, 아니면 null. */
  invoiceSeq?: number | null;
  invoiceNumber?: string | null;
  /** Application 결제 시 채워짐 */
  applicationSeq?: number | null;
  /** Concierge 결제 시 채워짐 */
  conciergeRequestSeq?: number | null;
}
