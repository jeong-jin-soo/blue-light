/**
 * 인증 상태 관리 스토어 (Zustand)
 */

import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { UserRole, LoginRequest, SignupRequest, TokenResponse } from '../types';
import { authApi } from '../api/authApi';
import { tokenUtils } from '../api/axiosClient';

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
      // 초기 상태
      user: null,
      isAuthenticated: tokenUtils.hasToken(),
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
    }
  )
);

export default useAuthStore;
