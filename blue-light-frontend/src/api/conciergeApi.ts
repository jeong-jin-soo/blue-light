/**
 * Concierge 신청 관련 Public API
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage A
 * - 기존 authApi.ts의 named function export + 집약 객체 패턴을 따름.
 */

import axiosClient from './axiosClient';

/** POST /api/public/concierge/request 요청 본문 */
export interface ConciergeRequestCreatePayload {
  fullName: string;
  email: string;
  mobileNumber: string;
  memo?: string;
  pdpaConsent: boolean;
  termsAgreed: boolean;
  signupConsent: boolean;
  delegationConsent: boolean;
  marketingOptIn: boolean;
  /** 클라이언트 스냅샷 약관 버전. 서버가 null이면 TermsVersion.CURRENT 사용. */
  termsVersion?: string;
}

/** POST /api/public/concierge/request 응답 */
export interface ConciergeRequestCreateResponse {
  publicCode: string;
  status: string;
  existingUser: boolean;
  accountSetupRequired: boolean;
  message: string;
}

/**
 * Concierge 신청 제출 (Public, 인증 불필요).
 * - 201 CREATED + ConciergeRequestCreateResponse
 * - 400: Validation 실패 (필수 동의 미체크, 이메일 포맷 등)
 * - 409: ACCOUNT_NOT_ELIGIBLE (SUSPENDED/DELETED)
 * - 422: STAFF_EMAIL_NOT_ALLOWED
 */
export const submitConciergeRequest = async (
  payload: ConciergeRequestCreatePayload
): Promise<ConciergeRequestCreateResponse> => {
  const response = await axiosClient.post<ConciergeRequestCreateResponse>(
    '/public/concierge/request',
    payload
  );
  return response.data;
};

export const conciergeApi = {
  submitRequest: submitConciergeRequest,
};

export default conciergeApi;
