import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Button } from '../../components/ui/Button';
import { authApi } from '../../api/authApi';
import { useToastStore } from '../../stores/toastStore';

/**
 * 이메일 인증 대기 페이지
 * - 가입 후 이메일 인증을 완료하지 않은 사용자에게 표시
 */
export default function EmailVerificationPendingPage() {
  const navigate = useNavigate();
  const { user, isAuthenticated, logout } = useAuthStore();
  const toast = useToastStore();
  const [resending, setResending] = useState(false);

  useEffect(() => {
    // 비로그인 상태면 로그인 페이지로
    if (!isAuthenticated || !user) {
      navigate('/login', { replace: true });
      return;
    }

    // 이미 인증된 경우 역할별 기본 페이지로
    if (user.emailVerified) {
      const dest = user.role === 'ADMIN' || user.role === 'LEW' ? '/admin/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [user, isAuthenticated, navigate]);

  const handleResendEmail = async () => {
    setResending(true);
    try {
      await authApi.resendVerificationEmail();
      toast.success('Verification email has been sent. Please check your inbox.');
    } catch (err) {
      const error = err as { message?: string };
      toast.error(error.message || 'Failed to resend verification email');
    } finally {
      setResending(false);
    }
  };

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  const handleSignInAgain = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <AuthLayout>
      <div className="text-center space-y-4">
        <div className="text-5xl mb-2">&#x2709;&#xFE0F;</div>

        <h2 className="text-xl font-semibold text-gray-800">
          Verify Your Email
        </h2>

        <p className="text-gray-600 text-sm leading-relaxed">
          We&apos;ve sent a verification email to <strong>{user?.email}</strong>.
          Please check your inbox and click the verification link to continue.
        </p>

        <div className="bg-info-50 border border-info-200 rounded-lg p-4 text-sm text-info-700">
          <p className="font-medium mb-1">What to do next?</p>
          <ul className="text-left space-y-1 text-xs">
            <li>1. Check your email inbox (and spam/junk folder).</li>
            <li>2. Click the verification link in the email.</li>
            <li>3. Sign in again to access the platform.</li>
          </ul>
        </div>

        <div className="flex flex-col gap-3 pt-2">
          <Button
            onClick={handleResendEmail}
            loading={resending}
            variant="outline"
          >
            Resend Verification Email
          </Button>

          <div className="flex gap-3 justify-center">
            <Button variant="ghost" onClick={handleLogout}>
              Logout
            </Button>
            <Button onClick={handleSignInAgain}>
              Sign in again
            </Button>
          </div>
        </div>

        <p className="text-xs text-gray-400 mt-4">
          If you continue to have issues, please contact the administrator.
        </p>
      </div>
    </AuthLayout>
  );
}
