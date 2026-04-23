/**
 * 클라이언트 사이드 유효성 검증 유틸리티
 * - 서버 사이드 validation은 그대로 유지 (defense-in-depth)
 * - 싱가포르 로컬 기준 규칙 포함
 */

/** 싱가포르 우편번호: 정확히 6자리 숫자 */
export function isValidPostalCode(value: string): boolean {
  return /^\d{6}$/.test(value.trim());
}

/** 이메일 형식 검증 */
export function isValidEmail(value: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim());
}

/** 싱가포르 전화번호: 8자리 숫자 (국가코드 제외) */
export function isValidPhone(value: string): boolean {
  // +65-XXXX-XXXX 형태나 8자리 숫자 모두 허용
  const digits = value.replace(/[\s\-+]/g, '');
  // 국가코드 포함 시
  if (digits.startsWith('65') && digits.length === 10) return true;
  // 순수 8자리
  return /^\d{8}$/.test(digits);
}

/**
 * 비밀번호 강도 검증
 * - 최소 8자
 * - 영문, 숫자, 특수문자 중 2가지 이상 조합
 */
export function validatePassword(value: string): string | null {
  if (value.length < 8) {
    return 'Password must be at least 8 characters';
  }
  let categories = 0;
  if (/[a-zA-Z]/.test(value)) categories++;
  if (/\d/.test(value)) categories++;
  if (/[^a-zA-Z\d]/.test(value)) categories++;
  if (categories < 2) {
    return 'Password must include at least 2 of: letters, numbers, special characters';
  }
  return null;
}

/** 필수 필드 검증 */
export function isRequired(value: string | null | undefined): boolean {
  return value != null && value.trim().length > 0;
}

/** 최소 길이 검증 */
export function minLength(value: string, min: number): boolean {
  return value.trim().length >= min;
}

/** 최대 길이 검증 */
export function maxLength(value: string, max: number): boolean {
  return value.trim().length <= max;
}

/**
 * 신청서 폼 검증 (step별)
 * - 에러 메시지 Record 반환 (빈 객체면 유효)
 */
export interface ApplicationFormData {
  applicationType: string;
  applicantType: 'INDIVIDUAL' | 'CORPORATE';
  spAccountNo: string;
  address: string;
  postalCode: string;
  /** P2.B — EMA ELISE 5-part Installation Address. 신규 폼에서 필수. */
  installationBlock?: string;
  installationUnit?: string;
  installationStreet?: string;
  installationBuilding?: string;
  installationPostalCode?: string;
  buildingType: string;
  selectedKva: number | null;
  /** Phase 5: "I don't know" 선택 시 true. true면 selectedKva 필수 검증 면제. */
  kvaUnknown?: boolean;
  originalApplicationSeq: number | null;
  existingLicenceNo: string;
  existingExpiryDate: string;
  renewalPeriodMonths: number | null;
  renewalReferenceNo: string;
  manualEntry: boolean;
}

export function validateApplicationStep0(formData: ApplicationFormData): Record<string, string> {
  const errors: Record<string, string> = {};

  if (!formData.renewalPeriodMonths) {
    errors.renewalPeriodMonths = 'Please select a licence period';
  }

  // AC-A3: applicantType 필수. 기본값 INDIVIDUAL이므로 실제로는 null이 될 수 없지만 방어적 검증.
  if (formData.applicantType !== 'INDIVIDUAL' && formData.applicantType !== 'CORPORATE') {
    errors.applicantType = 'Please select an applicant type';
  }

  if (formData.applicationType === 'RENEWAL') {
    if (formData.manualEntry) {
      if (!isRequired(formData.existingLicenceNo)) {
        errors.existingLicenceNo = 'Existing licence number is required';
      }
      if (!isRequired(formData.existingExpiryDate)) {
        errors.existingExpiryDate = 'Existing expiry date is required';
      }
    } else if (!formData.originalApplicationSeq) {
      errors.originalApplicationSeq = 'Please select an existing application';
    }
    if (!isRequired(formData.renewalReferenceNo)) {
      errors.renewalReferenceNo = 'Renewal reference number is required';
    }
  }

  return errors;
}

export function validateApplicationStep1(formData: ApplicationFormData): Record<string, string> {
  const errors: Record<string, string> = {};

  // P2.B — EMA ELISE 5-part Installation Address 필수 검증.
  // Block / Street / PostalCode 는 EMA 양식상 필수 3필드. Unit/Building 은 선택.
  // (Backend 는 아직 legacy address/postalCode 만 NotBlank 로 받지만, 프론트는 5-part 를 기준으로 잡는다.)
  if (!isRequired(formData.installationBlock || '')) {
    errors.installationBlock = 'Block / House No is required';
  }
  if (!isRequired(formData.installationStreet || '')) {
    errors.installationStreet = 'Street name is required';
  } else if (!minLength(formData.installationStreet || '', 3)) {
    errors.installationStreet = 'Street must be at least 3 characters';
  }

  if (!isRequired(formData.installationPostalCode || '')) {
    errors.installationPostalCode = 'Postal code is required';
  } else if (!isValidPostalCode(formData.installationPostalCode || '')) {
    errors.installationPostalCode = 'Postal code must be 6 digits (Singapore format)';
  }

  // P2.A: spAccountNo (legacy)는 msslHint로 통합되었고, hint 검증은 서버 warning-only이므로
  // 클라이언트 차단 없음. 어떤 hint 필드도 유효성 검증으로 신청을 막지 않는다(스펙 §5.1).

  return errors;
}

export function validateApplicationStep2(formData: ApplicationFormData): Record<string, string> {
  const errors: Record<string, string> = {};

  // Phase 5: "I don't know" 선택 시 selectedKva 필수 면제 — 서버가 45 강제
  if (!formData.kvaUnknown && !formData.selectedKva) {
    errors.selectedKva = 'Please select a kVA capacity, or choose "I don\'t know"';
  }

  return errors;
}

/**
 * Phase 2 PR#3: 싱가포르 UEN 형식 검증
 * - 9자리 숫자 + 1 알파벳 (예: 201812345A)
 * - 또는 8자리 숫자 + 1 알파벳 (Business 등록)
 * - 또는 T/S/R 프리픽스 10자리 (사업체)
 *
 * 백엔드 CompanyInfoRequest.uen 정규식과 동일하게 유지해야 함.
 */
export function isValidSingaporeUen(uen: string): boolean {
  if (!uen) return false;
  const re = /^(\d{8}[A-Z]|\d{9}[A-Z]|[TSR]\d{2}[A-Z]{2}\d{4}[A-Z])$/;
  return re.test(uen.trim());
}
