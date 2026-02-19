/**
 * Axios 클라이언트 설정
 * - Base URL 설정
 * - httpOnly 쿠키 기반 JWT 인증 (withCredentials)
 * - 401 에러 시 로그아웃 처리 (Response Interceptor)
 */

import axios from 'axios';
import type { AxiosError } from 'axios';
import type { ApiError } from '../types';

// 로컬 스토리지 키 (사용자 메타정보 전용 — 토큰은 httpOnly 쿠키에 저장)
const TOKEN_KEY = 'bluelight_token';

// Axios 인스턴스 생성
const axiosClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
  // httpOnly 쿠키를 모든 요청에 자동 포함
  withCredentials: true,
});

/**
 * Response Interceptor
 * - 401 에러 발생 시 로그아웃 처리
 * - 에러 응답 정규화
 */
axiosClient.interceptors.response.use(
  (response) => {
    return response;
  },
  (error: AxiosError<ApiError>) => {
    const { response } = error;

    if (response) {
      // 401 Unauthorized: 토큰 만료 또는 무효
      if (response.status === 401) {
        // 레거시 토큰 제거 + Zustand 인증 상태 제거
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem('bluelight-auth');

        // 로그인/회원가입 페이지가 아닌 경우에만 리다이렉트 (무한 루프 방지)
        const isAuthRequest = response.config.url?.includes('/auth/');
        const isAuthPage = window.location.pathname === '/login' || window.location.pathname === '/signup';
        if (!isAuthRequest && !isAuthPage) {
          sessionStorage.setItem('bluelight_logout_reason', 'session_expired');
          window.location.href = '/login';
        }
      }

      // 에러 메시지 추출 (필드별 검증 에러가 있으면 조합)
      const details = response.data?.details as Record<string, string> | undefined;
      const errorMessage = details
        ? Object.values(details).join('. ')
        : response.data?.message || 'An error occurred while processing the request';

      return Promise.reject({
        ...error,
        message: errorMessage,
        code: response.data?.code || 'UNKNOWN_ERROR',
      });
    }

    // 네트워크 오류
    if (error.request) {
      return Promise.reject({
        ...error,
        message: 'Unable to connect to the server. Please check your network.',
        code: 'NETWORK_ERROR',
      });
    }

    return Promise.reject(error);
  }
);

// 토큰 관리 유틸리티
// - 토큰은 httpOnly 쿠키에 저장 (서버에서 설정)
// - localStorage는 레거시 호환 + 토큰 만료 시간 확인용으로만 사용
export const tokenUtils = {
  /**
   * 토큰 저장 (레거시 호환: authStore에서 만료 검증용)
   */
  setToken: (token: string) => {
    localStorage.setItem(TOKEN_KEY, token);
  },

  /**
   * 토큰 조회 (만료 시간 확인용)
   */
  getToken: (): string | null => {
    return localStorage.getItem(TOKEN_KEY);
  },

  /**
   * 토큰 삭제
   */
  removeToken: () => {
    localStorage.removeItem(TOKEN_KEY);
  },

  /**
   * 토큰 존재 여부 확인
   */
  hasToken: (): boolean => {
    return !!localStorage.getItem(TOKEN_KEY);
  },
};

export default axiosClient;
