import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Button } from '../../components/ui/Button';
import { useToastStore } from '../../stores/toastStore';

/**
 * 미승인 LEW 대기 페이지
 * - LEW로 가입했지만 아직 ADMIN 승인을 받지 못한 사용자에게 표시
 * - "Check Status" 버튼으로 승인 여부 재확인 가능
 */
export default function LewPendingPage() {
  const navigate = useNavigate();
  const { user, isAuthenticated, logout, refreshUser } = useAuthStore();
  const toast = useToastStore();
  const [checking, setChecking] = useState(false);

  useEffect(() => {
    // 비로그인 상태면 로그인 페이지로
    if (!isAuthenticated || !user) {
      navigate('/login', { replace: true });
      return;
    }

    // 승인된 LEW면 LEW 대시보드로
    if (user.role === 'LEW' && user.approved) {
      navigate('/lew/dashboard', { replace: true });
      return;
    }

    // LEW가 아닌 경우 해당 역할의 기본 페이지로
    if (user.role !== 'LEW') {
      const dest = user.role === 'ADMIN' ? '/admin/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [user, isAuthenticated, navigate]);

  const handleCheckStatus = useCallback(async () => {
    setChecking(true);
    try {
      await refreshUser();
      // If approved, the useEffect above will redirect
      toast.info('Status checked — still pending approval.');
    } catch {
      toast.error('Failed to check status. Please try again.');
    } finally {
      setChecking(false);
    }
  }, [refreshUser, toast]);

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
        <div className="text-5xl mb-2">⏳</div>

        <h2 className="text-xl font-semibold text-gray-800">
          Account Pending Approval
        </h2>

        <p className="text-gray-600 text-sm leading-relaxed">
          Your LEW (Licensed Electrical Worker) account is awaiting administrator approval.
        </p>

        <div className="bg-info-50 border border-info-200 rounded-lg p-4 text-sm text-info-700">
          <p className="font-medium mb-1">What happens next?</p>
          <ul className="text-left space-y-1 text-xs">
            <li>1. An administrator will review your registration.</li>
            <li>2. Once approved, you&apos;ll have full access to the system.</li>
            <li>3. Click &quot;Check Status&quot; below to refresh, or sign in again later.</li>
          </ul>
        </div>

        <div className="flex flex-col gap-3 sm:flex-row sm:justify-center pt-2">
          <Button variant="outline" onClick={handleLogout}>
            Logout
          </Button>
          <Button variant="outline" onClick={handleCheckStatus} loading={checking}>
            🔄 Check Status
          </Button>
          <Button onClick={handleSignInAgain}>
            Sign in again
          </Button>
        </div>

        <p className="text-xs text-gray-400 mt-4">
          If you believe this is an error, please contact the administrator.
        </p>
      </div>
    </AuthLayout>
  );
}
