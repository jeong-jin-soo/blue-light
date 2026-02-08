/**
 * 인증 관련 API 함수
 */

import axiosClient, { tokenUtils } from './axiosClient';
import type { LoginRequest, SignupRequest, TokenResponse } from '../types';

/**
 * 회원가입
 * @param data 회원가입 정보
 * @returns 토큰 응답
 */
export const signup = async (data: SignupRequest): Promise<TokenResponse> => {
  const response = await axiosClient.post<TokenResponse>('/auth/signup', data);

  // 토큰 저장
  tokenUtils.setToken(response.data.accessToken);

  return response.data;
};

/**
 * 로그인
 * @param data 로그인 정보
 * @returns 토큰 응답
 */
export const login = async (data: LoginRequest): Promise<TokenResponse> => {
  const response = await axiosClient.post<TokenResponse>('/auth/login', data);

  // 토큰 저장
  tokenUtils.setToken(response.data.accessToken);

  return response.data;
};

/**
 * 로그아웃
 * - 클라이언트 측에서만 토큰 제거 (서버에 별도 요청 없음)
 */
export const logout = (): void => {
  tokenUtils.removeToken();
};

/**
 * 현재 로그인 상태 확인
 */
export const isAuthenticated = (): boolean => {
  return tokenUtils.hasToken();
};

export const authApi = {
  signup,
  login,
  logout,
  isAuthenticated,
};

export default authApi;
