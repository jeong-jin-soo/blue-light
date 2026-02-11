import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';

export default function LoginPage() {
  const navigate = useNavigate();
  const { login, isLoading, error, clearError, isAuthenticated, user } = useAuthStore();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [sessionExpiredMsg, setSessionExpiredMsg] = useState('');

  useEffect(() => {
    if (isAuthenticated && user) {
      // 미승인 LEW는 대기 페이지로
      if (user.role === 'LEW' && !user.approved) {
        navigate('/lew-pending', { replace: true });
        return;
      }
      const dest = user.role === 'ADMIN' ? '/admin/dashboard' : user.role === 'LEW' ? '/lew/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  // 세션 만료로 인한 리다이렉트 감지
  useEffect(() => {
    const reason = sessionStorage.getItem('bluelight_logout_reason');
    if (reason === 'session_expired') {
      sessionStorage.removeItem('bluelight_logout_reason');
      setSessionExpiredMsg('Your session has expired. Please sign in again.');
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    clearError();

    try {
      await login({ email, password });
    } catch {
      // error is managed by store
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

      {error && (
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
        <Link to="/signup" className="text-primary font-medium hover:underline">
          Create account
        </Link>
      </div>
    </AuthLayout>
  );
}
