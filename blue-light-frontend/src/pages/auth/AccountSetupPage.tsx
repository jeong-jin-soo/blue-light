/**
 * AccountSetupPage (/setup-account/:token)
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage C
 * - 토큰 검증 → 비밀번호 설정 → 자동 로그인 → 대시보드 리다이렉트
 *
 * 플로우:
 *   verifying (GET status) → form → submitting (POST complete) → done → redirect
 *   토큰 무효 시 → invalid 상태로 안내 (Login 페이지에서 활성화 링크 재요청 유도)
 */

import { useEffect, useState, type FormEvent } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import AuthLayout from '../../components/common/AuthLayout';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { useAuthStore } from '../../stores/authStore';
import { accountSetupApi } from '../../api/accountSetupApi';
import type { AccountSetupStatusResponse } from '../../api/accountSetupApi';
import type { ApiError, UserRole } from '../../types';

type Phase = 'verifying' | 'form' | 'submitting' | 'done' | 'invalid';

/** axiosClient interceptor가 정규화한 에러(spread된 객체) + AxiosError 양쪽에서 code/status 추출 */
interface NormalizedHttpError {
  response?: { status?: number; data?: ApiError };
  code?: string;
  message?: string;
}

/** UserRole → 기본 홈 경로 매핑 (LoginPage.tsx의 리다이렉트 로직과 일치) */
function roleHomePath(role: UserRole): string {
  if (role === 'SYSTEM_ADMIN') return '/admin/system';
  if (role === 'ADMIN') return '/admin/dashboard';
  if (role === 'LEW') return '/lew/dashboard';
  if (role === 'SLD_MANAGER') return '/sld-manager/dashboard';
  if (role === 'CONCIERGE_MANAGER') return '/concierge-manager/dashboard';
  return '/dashboard';
}

export default function AccountSetupPage() {
  const { token } = useParams<{ token: string }>();
  const navigate = useNavigate();
  const setUserFromToken = useAuthStore((s) => s.setUserFromToken);

  const [phase, setPhase] = useState<Phase>('verifying');
  const [status, setStatus] = useState<AccountSetupStatusResponse | null>(null);
  const [invalidReason, setInvalidReason] = useState<string | null>(null);

  const [password, setPassword] = useState('');
  const [passwordConfirm, setPasswordConfirm] = useState('');
  const [formError, setFormError] = useState<string | null>(null);

  // ── Step 1: 토큰 검증 ──
  useEffect(() => {
    if (!token) return;
    let cancelled = false;

    (async () => {
      try {
        const result = await accountSetupApi.getStatus(token);
        if (!cancelled) {
          setStatus(result);
          setPhase('form');
        }
      } catch (err) {
        if (cancelled) return;
        const e = err as NormalizedHttpError;
        const code = e.code ?? e.response?.data?.code;
        let reason = 'This activation link is invalid or has expired.';
        if (code === 'TOKEN_ALREADY_USED') {
          reason = 'This activation link has already been used.';
        } else if (code === 'TOKEN_LOCKED') {
          reason =
            'This activation link is locked due to too many failed attempts. Please request a new one from the login page.';
        } else if (code === 'TOKEN_EXPIRED') {
          reason = 'This activation link has expired (valid for 48 hours).';
        } else if (code === 'TOKEN_REVOKED') {
          reason =
            'This activation link has been replaced by a newer one. Please check your latest email.';
        }
        setInvalidReason(reason);
        setPhase('invalid');
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [token]);

  // ── 비밀번호 강도 (간이 1~5 스케일) ──
  const passwordStrength = (() => {
    if (!password) return 0;
    let score = 0;
    if (password.length >= 8) score++;
    if (password.length >= 12) score++;
    if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^a-zA-Z0-9]/.test(password)) score++;
    return score;
  })();

  const strengthLabel =
    passwordStrength <= 2
      ? 'Weak'
      : passwordStrength <= 3
      ? 'Fair'
      : passwordStrength <= 4
      ? 'Good'
      : 'Strong';

  const strengthColor =
    passwordStrength <= 2
      ? 'bg-red-500'
      : passwordStrength <= 3
      ? 'bg-yellow-500'
      : passwordStrength <= 4
      ? 'bg-blue-500'
      : 'bg-green-500';

  const canSubmit =
    password.length >= 8 &&
    password === passwordConfirm &&
    phase === 'form';

  // ── Step 2: 비밀번호 설정 제출 ──
  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!token || !canSubmit) return;
    setFormError(null);
    setPhase('submitting');

    try {
      const tokenResponse = await accountSetupApi.complete(token, {
        password,
        passwordConfirm,
      });
      // accountSetupApi.complete()가 tokenUtils.setToken 자동 호출(Stage A).
      // authStore 상태를 TokenResponse로 즉시 갱신하여 ProtectedRoute 통과.
      setUserFromToken(tokenResponse);

      setPhase('done');
      setTimeout(() => {
        navigate(roleHomePath(tokenResponse.role), { replace: true });
      }, 1500);
    } catch (err) {
      const e = err as NormalizedHttpError;
      const code = e.code ?? e.response?.data?.code;
      const status = e.response?.status;
      let msg = 'Something went wrong. Please try again.';

      if (code === 'PASSWORD_POLICY_VIOLATION') {
        msg =
          e.response?.data?.message ||
          'Password does not meet requirements. Use 8~72 characters with both letters and numbers (no spaces).';
      } else if (code === 'PASSWORD_MISMATCH') {
        msg = 'Passwords do not match.';
      } else if (status === 410) {
        msg = 'Activation link has expired or is invalid. Please request a new one.';
      } else if (e.response?.data?.message) {
        msg = e.response.data.message;
      } else if (e.message) {
        msg = e.message;
      }
      setFormError(msg);
      setPhase('form');
    }
  };

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  // ── 토큰 검증 중 ──
  if (phase === 'verifying') {
    return (
      <AuthLayout>
        <div className="text-center py-8">
          <div
            className="animate-spin rounded-full h-10 w-10 border-2 border-primary border-t-transparent mx-auto mb-4"
            role="status"
            aria-label="Verifying activation link"
          />
          <p className="text-gray-700">Verifying activation link...</p>
        </div>
      </AuthLayout>
    );
  }

  // ── 토큰 무효 ──
  if (phase === 'invalid') {
    return (
      <AuthLayout>
        <div className="text-center">
          <div className="w-12 h-12 mx-auto mb-4 rounded-full bg-error-50 flex items-center justify-center">
            <svg
              aria-hidden="true"
              className="w-7 h-7 text-error-600"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z"
              />
            </svg>
          </div>
          <h1 className="text-xl font-bold text-gray-900 mb-2">Link unavailable</h1>
          <p className="text-gray-700 mb-6">{invalidReason}</p>
          <Button variant="primary" onClick={() => navigate('/login')}>
            Go to login
          </Button>
          <p className="mt-3 text-xs text-gray-500">
            On the login page, you can request a new activation link.
          </p>
        </div>
      </AuthLayout>
    );
  }

  // ── 완료 (잠시 표시 후 대시보드 리다이렉트) ──
  if (phase === 'done') {
    return (
      <AuthLayout>
        <div className="text-center py-8">
          <div className="w-14 h-14 mx-auto mb-4 rounded-full bg-success-100 flex items-center justify-center">
            <svg
              aria-hidden="true"
              className="w-9 h-9 text-success-600"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
              />
            </svg>
          </div>
          <h1 className="text-xl font-bold text-gray-900 mb-1">Account activated!</h1>
          <p className="text-gray-700 text-sm">Redirecting to your dashboard...</p>
        </div>
      </AuthLayout>
    );
  }

  // ── Form (phase === 'form' | 'submitting') ──
  const submitting = phase === 'submitting';
  return (
    <AuthLayout>
      <div className="text-center mb-6">
        <h1 className="text-xl font-bold text-gray-900 mb-1">Set your password</h1>
        <p className="text-gray-600 text-sm">
          Activating <strong>{status?.maskedEmail}</strong>
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <div>
          <Input
            label="New password"
            name="password"
            type="password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            hint="At least 8 characters with letters and numbers."
            autoComplete="new-password"
            maxLength={72}
            disabled={submitting}
          />
          {password && (
            <div className="mt-2" aria-live="polite">
              <div className="w-full h-1.5 bg-gray-200 rounded overflow-hidden">
                <div
                  className={`h-full ${strengthColor} transition-all`}
                  style={{ width: `${(passwordStrength / 5) * 100}%` }}
                />
              </div>
              <p className="mt-1 text-xs text-gray-600">Strength: {strengthLabel}</p>
            </div>
          )}
        </div>

        <Input
          label="Confirm password"
          name="passwordConfirm"
          type="password"
          required
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
          error={
            passwordConfirm && password !== passwordConfirm
              ? 'Passwords do not match.'
              : undefined
          }
          autoComplete="new-password"
          maxLength={72}
          disabled={submitting}
        />

        {formError && (
          <div
            role="alert"
            className="p-3 rounded-md bg-error-50 border border-error-200 text-sm text-error-600"
          >
            {formError}
          </div>
        )}

        <Button
          type="submit"
          variant="primary"
          size="lg"
          fullWidth
          disabled={!canSubmit}
          loading={submitting}
        >
          Activate account
        </Button>
      </form>
    </AuthLayout>
  );
}
