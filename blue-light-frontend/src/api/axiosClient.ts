/**
 * Axios 클라이언트 설정
 * - Base URL 설정
 * - JWT 토큰 자동 삽입 (Request Interceptor)
 * - 401 에러 시 로그아웃 처리 (Response Interceptor)
 */

import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig } from 'axios';
import type { ApiError } from '../types';

// 로컬 스토리지 키
const TOKEN_KEY = 'bluelight_token';

// Axios 인스턴스 생성
const axiosClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

/**
 * Request Interceptor
 * - 로컬 스토리지에서 JWT 토큰을 꺼내 Authorization 헤더에 자동 삽입
 */
axiosClient.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = localStorage.getItem(TOKEN_KEY);

    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

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
        // 토큰 제거
        localStorage.removeItem(TOKEN_KEY);

        // 로그인 페이지로 리다이렉트 (auth 관련 요청 제외)
        const isAuthRequest = response.config.url?.includes('/auth/');
        if (!isAuthRequest) {
          window.location.href = '/login';
        }
      }

      // 에러 메시지 추출
      const errorMessage = response.data?.message || 'An error occurred while processing the request';

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
export const tokenUtils = {
  /**
   * 토큰 저장
   */
  setToken: (token: string) => {
    localStorage.setItem(TOKEN_KEY, token);
  },

  /**
   * 토큰 조회
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
