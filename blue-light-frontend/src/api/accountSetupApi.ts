/**
 * Account Setup 관련 Public API
 * - Kaki Concierge v1.5 Phase 1 PR#2 Stage A / PR#3 Stage A
 * - 토큰 UUID 기반 비밀번호 설정 플로우.
 */

import axiosClient, { tokenUtils } from './axiosClient';
import type { TokenResponse } from '../types';

/** GET /api/public/account-setup/{token} 응답 */
export interface AccountSetupStatusResponse {
  /** 마스킹된 이메일 (예: "a***@example.com") */
  maskedEmail: string;
  /** 토큰 만료 시각 (ISO-8601) */
  expiresAt: string;
}

/** POST /api/public/account-setup/{token} 요청 본문 */
export interface AccountSetupCompletePayload {
  password: string;
  passwordConfirm: string;
}

/**
 * 토큰 상태 조회 (Setup 페이지 진입 시 마스킹 이메일/만료 시각 노출).
 * - 410 GONE: TOKEN_INVALID / TOKEN_ALREADY_USED / TOKEN_LOCKED / TOKEN_REVOKED / TOKEN_EXPIRED
 */
export const getAccountSetupStatus = async (
  token: string
): Promise<AccountSetupStatusResponse> => {
  const response = await axiosClient.get<AccountSetupStatusResponse>(
    `/public/account-setup/${encodeURIComponent(token)}`
  );
  return response.data;
};

/**
 * 비밀번호 설정 완료 (토큰 markUsed + status=ACTIVE 전이 + JWT 발급).
 * 성공 시 TokenResponse 반환 — tokenUtils에 저장하여 자동 로그인.
 *
 * - 400: PASSWORD_MISMATCH / PASSWORD_POLICY_VIOLATION
 * - 410 GONE: 토큰 무효/만료/잠김
 */
export const completeAccountSetup = async (
  token: string,
  payload: AccountSetupCompletePayload
): Promise<TokenResponse> => {
  const response = await axiosClient.post<TokenResponse>(
    `/public/account-setup/${encodeURIComponent(token)}`,
    payload
  );
  tokenUtils.setToken(response.data.accessToken);
  return response.data;
};

export const accountSetupApi = {
  getStatus: getAccountSetupStatus,
  complete: completeAccountSetup,
};

export default accountSetupApi;
