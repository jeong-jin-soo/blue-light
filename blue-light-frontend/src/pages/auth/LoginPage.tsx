import { useState, useEffect } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { requestActivationLink } from '../../api/authApi';
import type { ApiError } from '../../types';

/** axiosClient interceptor가 정규화한 에러(spread된 객체) + AxiosError 양쪽에서 code/status 추출 */
interface NormalizedHttpError {
  response?: { status?: number; data?: ApiError };
  code?: string;
  message?: string;
}

export default function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { login, isLoading, error, clearError, isAuthenticated, user } = useAuthStore();

  // Applicant 가입 전 원래 요청 페이지로 돌려보낼 returnTo URL
  const rawReturnTo = searchParams.get('returnTo');
  const returnTo = rawReturnTo && rawReturnTo.startsWith('/') && !rawReturnTo.startsWith('//')
    ? rawReturnTo
    : null;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [sessionExpiredMsg, setSessionExpiredMsg] = useState('');

  // ── Kaki Concierge v1.5 Phase 1 PR#3 Stage C: 활성화 링크 플로우 ──
  const [pendingActivationEmail, setPendingActivationEmail] = useState<string | null>(null);
  const [suspendedMessage, setSuspendedMessage] = useState<string | null>(null);
  const [activationLinkSending, setActivationLinkSending] = useState(false);
  const [activationLinkMessage, setActivationLinkMessage] = useState<string | null>(null);

  useEffect(() => {
    if (isAuthenticated && user) {
      // 미승인 LEW는 대기 페이지로
      if (user.role === 'LEW' && !user.approved) {
        navigate('/lew-pending', { replace: true });
        return;
      }
      // Applicant이고 returnTo가 있으면 해당 페이지로 리다이렉트
      if (user.role === 'APPLICANT' && returnTo) {
        navigate(returnTo, { replace: true });
        return;
      }
      const dest = user.role === 'SYSTEM_ADMIN' ? '/admin/system'
        : user.role === 'ADMIN' ? '/admin/dashboard'
        : user.role === 'LEW' ? '/lew/dashboard'
        : user.role === 'SLD_MANAGER' ? '/sld-manager/dashboard'
        : user.role === 'CONCIERGE_MANAGER' ? '/concierge-manager/dashboard'
        : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate, returnTo]);

  // 세션 만료로 인한 리다이렉트 감지
  useEffect(() => {
    const reason = sessionStorage.getItem('licensekaki_logout_reason');
    if (reason === 'session_expired') {
      sessionStorage.removeItem('licensekaki_logout_reason');
      setSessionExpiredMsg('Your session has expired. Please sign in again.');
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    clearError();
    // Concierge 상태 패널도 초기화
    setPendingActivationEmail(null);
    setSuspendedMessage(null);
    setActivationLinkMessage(null);

    try {
      await login({ email, password });
    } catch (err) {
      // store에도 error 가 저장되지만, code 감지는 원본 error 객체에서 수행
      const e = err as NormalizedHttpError;
      const code = e.code ?? e.response?.data?.code;

      if (code === 'ACCOUNT_PENDING_ACTIVATION') {
        // 일반 에러 배너 대신 Concierge 활성화 패널 표시
        setPendingActivationEmail(email);
        clearError();
      } else if (code === 'ACCOUNT_SUSPENDED') {
        setSuspendedMessage(
          'Your account has been suspended. Please contact support at support@licensekaki.sg.'
        );
        clearError();
      }
      // 그 외(INVALID_CREDENTIALS 등)는 store의 error 상태 그대로 사용
    }
  };

  const handleRequestActivation = async () => {
    if (!pendingActivationEmail) return;
    setActivationLinkSending(true);
    setActivationLinkMessage(null);

    const FIXED_MESSAGE =
      "If this email is registered and eligible for activation, we've sent an activation link.";

    try {
      const result = await requestActivationLink({ email: pendingActivationEmail });
      setActivationLinkMessage(result.message || FIXED_MESSAGE);
    } catch (err) {
      const e = err as NormalizedHttpError;
      if (e.response?.status === 429) {
        setActivationLinkMessage('Too many requests. Please try again later.');
      } else {
        // 5케이스 동일 응답 원칙 — 실패해도 고정 메시지 유지
        setActivationLinkMessage(FIXED_MESSAGE);
      }
    } finally {
      setActivationLinkSending(false);
    }
  };

  return (
    <AuthLayout>
      <h2 className="text-xl font-semibold text-gray-800 mb-6">Sign in to your account</h2>

      {sessionExpiredMsg && (
        <div className="mb-4 p-3 bg-warning-50 border border-warning-200 rounded-lg text-sm text-warning-700">
          {sessionExpiredMsg}
        </div>
      )}

      {/* ★ Kaki Concierge v1.5 Phase 1 PR#3 Stage C: PENDING_ACTIVATION 패널 */}
      {pendingActivationEmail && (
        <div
          className="mb-4 p-4 rounded-lg bg-concierge-50 border border-concierge-300"
          role="alert"
        >
          <h3 className="text-sm font-semibold text-concierge-800 mb-1">
            Account pending activation
          </h3>
          <p className="text-sm text-gray-800 mb-3">
            Your account at <strong className="break-all">{pendingActivationEmail}</strong>{' '}
            hasn&apos;t been activated yet. Request a new activation link to set your
            password.
          </p>
          {activationLinkMessage ? (
            <p className="text-sm text-gray-700 bg-white rounded p-2 border border-gray-200">
              {activationLinkMessage}
            </p>
          ) : (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="concierge"
                size="sm"
                onClick={handleRequestActivation}
                loading={activationLinkSending}
              >
                Send activation link
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setPendingActivationEmail(null)}
                disabled={activationLinkSending}
              >
                Cancel
              </Button>
            </div>
          )}
        </div>
      )}

      {/* ★ Stage C: SUSPENDED 안내 */}
      {suspendedMessage && (
        <div
          className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg text-sm text-error-600"
          role="alert"
        >
          {suspendedMessage}
        </div>
      )}

      {/* 일반 에러 (INVALID_CREDENTIALS 등) — PENDING/SUSPENDED 분기 시에는 클리어됨 */}
      {error && !pendingActivationEmail && !suspendedMessage && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg text-sm text-error-600">
          {error}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <Input
          label="Email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="you@example.com"
        />

        <Input
          label="Password"
          type="password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="Enter your password"
        />

        <Button type="submit" fullWidth loading={isLoading}>
          Sign In
        </Button>
      </form>

      <div className="mt-4 text-center">
        <Link to="/forgot-password" className="text-sm text-primary font-medium hover:underline">
          Forgot your password?
        </Link>
      </div>

      <div className="mt-4 text-center text-sm text-gray-500">
        Don&apos;t have an account?{' '}
        <Link
          to={returnTo ? `/signup?role=APPLICANT&returnTo=${encodeURIComponent(returnTo)}` : '/signup'}
          className="text-primary font-medium hover:underline"
        >
          Create account
        </Link>
      </div>
    </AuthLayout>
  );
}
