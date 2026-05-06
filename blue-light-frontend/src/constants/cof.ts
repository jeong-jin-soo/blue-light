/**
 * Certificate of Fitness (CoF) 관련 상수 — 신청자 optional fast-track 섹션과
 * LEW Review Form(P2.B)에서 공유된다. 라벨은 EMA ELISE / SP Retailer 용어를
 * 그대로 표기하되, 신청자에게는 중립적으로 번역한 보조 설명을 함께 두 수 있다.
 */

/**
 * Consumer Type — SP Group 전기 소매 구조.
 * - NON_CONTESTABLE: SP Services Limited 고정 (기본값)
 * - CONTESTABLE: 본인이 전기 소매사업자와 계약 (45kVA 이상 관례)
 */
export type ConsumerType = 'NON_CONTESTABLE' | 'CONTESTABLE';

export interface ConsumerTypeOption {
  value: ConsumerType;
  label: string;
  description: string;
}

export const CONSUMER_TYPE_OPTIONS: ConsumerTypeOption[] = [
  {
    value: 'NON_CONTESTABLE',
    label: 'Non-contestable (SP Services)',
    description: 'Electricity is supplied by SP Services. Most homes and small premises use this.',
  },
  {
    value: 'CONTESTABLE',
    label: 'Contestable (retailer of your choice)',
    description: 'You signed a contract with an electricity retailer. Common for larger premises.',
  },
];

/**
 * Retailer 목록 — 스펙 §3.3 마스터.
 * 백엔드 RetailerCode enum 문자열과 1:1.
 */
export type RetailerCode =
  | 'SP_SERVICES_LIMITED'
  | 'KEPPEL_ELECTRIC'
  | 'TUAS_POWER_SUPPLY'
  | 'SEMBCORP_POWER'
  | 'GENECO'
  | 'SENOKO_ENERGY_SUPPLY'
  | 'BEST_ELECTRICITY'
  | 'PACIFICLIGHT_ENERGY'
  | 'DIAMOND_ELECTRIC'
  | 'UNION_POWER'
  | 'SUNSEAP_ENERGY'
  | 'OTHER';

export interface RetailerOption {
  value: RetailerCode;
  label: string;
}

export const RETAILER_OPTIONS: RetailerOption[] = [
  { value: 'SP_SERVICES_LIMITED', label: 'SP Services Limited' },
  { value: 'KEPPEL_ELECTRIC', label: 'Keppel Electric' },
  { value: 'TUAS_POWER_SUPPLY', label: 'Tuas Power Supply' },
  { value: 'SEMBCORP_POWER', label: 'Sembcorp Power' },
  { value: 'GENECO', label: 'Geneco' },
  { value: 'SENOKO_ENERGY_SUPPLY', label: 'Senoko Energy Supply' },
  { value: 'BEST_ELECTRICITY', label: 'Best Electricity' },
  { value: 'PACIFICLIGHT_ENERGY', label: 'PacificLight Energy' },
  { value: 'DIAMOND_ELECTRIC', label: 'Diamond Electric' },
  { value: 'UNION_POWER', label: 'Union Power' },
  { value: 'SUNSEAP_ENERGY', label: 'Sunseap Energy' },
  { value: 'OTHER', label: 'Other' },
];

/**
 * Supply Voltage 옵션 — DB CHECK 제약: 230 / 400 / 6600 / 22000.
 * label은 친숙한 kV 표기 병행.
 */
export interface SupplyVoltageOption {
  value: number;
  label: string;
}

export const SUPPLY_VOLTAGE_OPTIONS: SupplyVoltageOption[] = [
  { value: 230, label: '230V (single-phase)' },
  { value: 400, label: '400V (three-phase)' },
  { value: 6600, label: '6.6 kV' },
  { value: 22000, label: '22 kV' },
];

/**
 * MSSL 마스킹 유틸 — last4만 노출.
 * 입력이 비어있거나 last4가 없으면 null 반환.
 */
export function formatMsslMasked(last4?: string | null): string | null {
  if (!last4) return null;
  const trimmed = last4.trim();
  if (!trimmed) return null;
  // 형식: "•••-••-••••-X" (10자리 — 3-2-4-1, 마지막 1자리만 노출)
  // 백엔드 last4가 실제로는 마지막 4자리를 주므로 3-2-4-1의 마지막 1자리만 표시하도록 뒤 1글자 사용
  const lastChar = trimmed.slice(-1);
  return `•••-••-••••-${lastChar}`;
}

/**
 * 공백/대시 혼합 입력된 MSSL을 서버 포맷(3-2-4-1)으로 정규화.
 * 10자리 숫자를 추출하여 "AAA-BB-CCCC-D" 로 변환.
 * 10자리가 아니면 원문 그대로 반환 (서버가 warning 처리).
 */
export function normalizeMsslHint(raw: string | undefined | null): string | undefined {
  if (!raw) return undefined;
  const trimmed = raw.trim();
  if (!trimmed) return undefined;
  const digits = trimmed.replace(/\D/g, '');
  if (digits.length !== 10) {
    // 부분 입력 — 서버에 그대로 전달해 warning으로 저장 생략
    return trimmed;
  }
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5, 9)}-${digits.slice(9, 10)}`;
}
