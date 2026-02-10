import { useState, useEffect } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { authApi } from '../../api/authApi';

export default function ResetPasswordPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const token = searchParams.get('token');

  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState(false);

  useEffect(() => {
    if (!token) {
      setError('Invalid reset link. Please request a new password reset.');
    }
  }, [token]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (password !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }

    if (password.length < 8) {
      setError('Password must be at least 8 characters');
      return;
    }

    if (!token) {
      setError('Invalid reset link');
      return;
    }

    setIsLoading(true);

    try {
      await authApi.resetPassword({ token, newPassword: password });
      setSuccess(true);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'Failed to reset password. The link may have expired.';
      setError(message);
    } finally {
      setIsLoading(false);
    }
  };

  if (success) {
    return (
      <AuthLayout>
        <div className="text-center">
          <div className="text-4xl mb-4">&#10003;</div>
          <h2 className="text-xl font-semibold text-gray-800 mb-3">Password reset successful</h2>
          <p className="text-sm text-gray-600 mb-6">
            Your password has been updated. You can now sign in with your new password.
          </p>
          <Button
            fullWidth
            onClick={() => navigate('/login', { replace: true })}
          >
            Sign In
          </Button>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout>
      <h2 className="text-xl font-semibold text-gray-800 mb-2">Reset your password</h2>
      <p className="text-sm text-gray-500 mb-6">
        Enter your new password below.
      </p>

      {error && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg text-sm text-error-600">
          {error}
        </div>
      )}

      {!token ? (
        <div className="text-center">
          <p className="text-sm text-gray-600 mb-4">
            This reset link is invalid or has expired.
          </p>
          <Link
            to="/forgot-password"
            className="text-primary font-medium hover:underline text-sm"
          >
            Request a new reset link
          </Link>
        </div>
      ) : (
        <>
          <form onSubmit={handleSubmit} className="space-y-5">
            <Input
              label="New Password"
              type="password"
              required
              minLength={8}
              maxLength={20}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="8-20 characters"
            />

            <Input
              label="Confirm New Password"
              type="password"
              required
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              placeholder="Re-enter your new password"
            />

            <Button type="submit" fullWidth loading={isLoading}>
              Reset Password
            </Button>
          </form>

          <div className="mt-6 text-center text-sm text-gray-500">
            <Link to="/login" className="text-primary font-medium hover:underline">
              Back to Sign In
            </Link>
          </div>
        </>
      )}
    </AuthLayout>
  );
}
