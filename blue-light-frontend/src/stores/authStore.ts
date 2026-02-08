/**
 * 인증 상태 관리 스토어 (Zustand)
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { UserRole, LoginRequest, SignupRequest, TokenResponse } from '../types';
import { authApi } from '../api/authApi';
import { tokenUtils } from '../api/axiosClient';

/**
 * JWT 토큰의 만료 여부를 확인
 * - 토큰이 없거나 디코딩 실패 시 false 반환
 * - exp 클레임 기준 만료 여부 판단 (60초 버퍼)
 */
function isTokenValid(): boolean {
  const token = tokenUtils.getToken();
  if (!token) return false;

  try {
    const payload = JSON.parse(atob(token.split('.')[1]));
    const now = Math.floor(Date.now() / 1000);
    return payload.exp > now + 60; // 60초 버퍼
  } catch {
    return false;
  }
}

/**
 * 인증 사용자 정보
 */
interface AuthUser {
  userSeq: number;
  email: string;
  name: string;
  role: UserRole;
}

/**
 * 인증 스토어 상태
 */
interface AuthState {
  // 상태
  user: AuthUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  error: string | null;

  // 액션
  login: (data: LoginRequest) => Promise<void>;
  signup: (data: SignupRequest) => Promise<void>;
  logout: () => void;
  clearError: () => void;
  setUserFromToken: (tokenResponse: TokenResponse) => void;
}

/**
 * Auth Store
 */
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      // 초기 상태 — 토큰 존재 + 만료되지 않은 경우만 인증 상태 유지
      user: null,
      isAuthenticated: isTokenValid(),
      isLoading: false,
      error: null,

      // 로그인
      login: async (data: LoginRequest) => {
        set({ isLoading: true, error: null });

        try {
          const response = await authApi.login(data);

          set({
            user: {
              userSeq: response.userSeq,
              email: response.email,
              name: response.name,
              role: response.role,
            },
            isAuthenticated: true,
            isLoading: false,
            error: null,
          });
        } catch (err) {
          const error = err as { message?: string };
          set({
            user: null,
            isAuthenticated: false,
            isLoading: false,
            error: error.message || '로그인에 실패했습니다',
          });
          throw err;
        }
      },

      // 회원가입
      signup: async (data: SignupRequest) => {
        set({ isLoading: true, error: null });

        try {
          const response = await authApi.signup(data);

          set({
            user: {
              userSeq: response.userSeq,
              email: response.email,
              name: response.name,
              role: response.role,
            },
            isAuthenticated: true,
            isLoading: false,
            error: null,
          });
        } catch (err) {
          const error = err as { message?: string };
          set({
            user: null,
            isAuthenticated: false,
            isLoading: false,
            error: error.message || '회원가입에 실패했습니다',
          });
          throw err;
        }
      },

      // 로그아웃
      logout: () => {
        authApi.logout();
        set({
          user: null,
          isAuthenticated: false,
          isLoading: false,
          error: null,
        });
      },

      // 에러 클리어
      clearError: () => {
        set({ error: null });
      },

      // 토큰 응답으로 사용자 설정
      setUserFromToken: (tokenResponse: TokenResponse) => {
        set({
          user: {
            userSeq: tokenResponse.userSeq,
            email: tokenResponse.email,
            name: tokenResponse.name,
            role: tokenResponse.role,
          },
          isAuthenticated: true,
        });
      },
    }),
    {
      name: 'bluelight-auth',
      partialize: (state) => ({
        user: state.user,
        isAuthenticated: state.isAuthenticated,
      }),
      onRehydrateStorage: () => (state) => {
        // localStorage에서 복원된 후 토큰 만료 여부 재검증
        if (state && state.isAuthenticated && !isTokenValid()) {
          state.user = null;
          state.isAuthenticated = false;
          tokenUtils.removeToken();
        }
      },
    }
  )
);

export default useAuthStore;
