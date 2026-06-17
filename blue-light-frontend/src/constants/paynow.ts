// LEW 본인 PayNow 수취 계정 — 프론트엔드 검증 단일 소스(R-PN5).
// 백엔드 PaynowValidator.java 와 동일한 정규식을 유지해야 한다 (drift 금지).
//   - MOBILE: 8 또는 9 로 시작하는 8자리 숫자 (예 97771983)
//   - COMPANY_UEN: 9자리 숫자 + 끝 영문 1자 = 10자 (예 201837490N)
// 형식·자리수는 싱가포르 PayNow 의 법적·고정 형식이므로 상수로 정의한다.

export const PAYNOW_TYPES = ['COMPANY_UEN', 'MOBILE'] as const;
export type PaynowType = typeof PAYNOW_TYPES[number];

export const PAYNOW_TYPE_LABELS: Record<PaynowType, string> = {
  COMPANY_UEN: 'Company UEN PayNow',
  MOBILE: 'Mobile PayNow',
};

export const PAYNOW_PLACEHOLDER: Record<PaynowType, string> = {
  COMPANY_UEN: '201837490N',
  MOBILE: '97771983',
};

// 백엔드 PaynowValidator 와 동일 (R-PN5)
export const PAYNOW_MOBILE_REGEX = /^[89]\d{7}$/;
export const PAYNOW_COMPANY_UEN_REGEX = /^\d{9}[A-Za-z]$/;

/** PayNow 값이 선택한 유형의 형식에 맞는지 검증. */
export function isValidPaynow(type: PaynowType, value: string): boolean {
  const trimmed = (value ?? '').trim();
  if (!trimmed) return false;
  switch (type) {
    case 'MOBILE':
      return PAYNOW_MOBILE_REGEX.test(trimmed);
    case 'COMPANY_UEN':
      return PAYNOW_COMPANY_UEN_REGEX.test(trimmed);
    default:
      return false;
  }
}
