import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import AuthLayout from '../../components/common/AuthLayout';
import { Button } from '../../components/ui/Button';
import { authApi } from '../../api/authApi';
import { useAuthStore } from '../../stores/authStore';

/**
 * 이메일 인증 완료 페이지
 * - 이메일 링크 클릭 시 토큰을 검증하고 결과를 표시
 */
export default function VerifyEmailPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { logout } = useAuthStore();
  const [status, setStatus] = useState<'verifying' | 'success' | 'error'>('verifying');
  const [errorMessage, setErrorMessage] = useState('');

  useEffect(() => {
    const token = searchParams.get('token');
    if (!token) {
      setStatus('error');
      setErrorMessage('No verification token provided.');
      return;
    }

    authApi.verifyEmail(token)
      .then(() => {
        setStatus('success');
      })
      .catch((err: { message?: string }) => {
        setStatus('error');
        setErrorMessage(err.message || 'Failed to verify email. The link may be invalid or expired.');
      });
  }, [searchParams]);

  const handleLogin = () => {
    // 기존 세션 정리 후 로그인 페이지로
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <AuthLayout>
      <div className="text-center space-y-4">
        {status === 'verifying' && (
          <>
            <div className="text-5xl mb-2">&#x23F3;</div>
            <h2 className="text-xl font-semibold text-gray-800">
              Verifying your email...
            </h2>
            <p className="text-gray-600 text-sm">
              Please wait while we verify your email address.
            </p>
          </>
        )}

        {status === 'success' && (
          <>
            <div className="text-5xl mb-2">&#x2705;</div>
            <h2 className="text-xl font-semibold text-gray-800">
              Email Verified!
            </h2>
            <p className="text-gray-600 text-sm leading-relaxed">
              Your email has been verified successfully. You can now sign in to access the platform.
            </p>
            <div className="pt-2">
              <Button onClick={handleLogin}>
                Sign In
              </Button>
            </div>
          </>
        )}

        {status === 'error' && (
          <>
            <div className="text-5xl mb-2">&#x274C;</div>
            <h2 className="text-xl font-semibold text-gray-800">
              Verification Failed
            </h2>
            <p className="text-gray-600 text-sm leading-relaxed">
              {errorMessage}
            </p>
            <div className="pt-2">
              <Button onClick={handleLogin}>
                Go to Login
              </Button>
            </div>
          </>
        )}
      </div>
    </AuthLayout>
  );
}
