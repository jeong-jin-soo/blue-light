import { useState } from 'react';
import { Link } from 'react-router-dom';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { authApi } from '../../api/authApi';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setIsLoading(true);

    try {
      await authApi.forgotPassword({ email });
      setSubmitted(true);
    } catch (err: unknown) {
      const message = (err as { message?: string })?.message || 'An error occurred. Please try again.';
      setError(message);
    } finally {
      setIsLoading(false);
    }
  };

  if (submitted) {
    return (
      <AuthLayout>
        <div className="text-center">
          <div className="text-4xl mb-4">📧</div>
          <h2 className="text-xl font-semibold text-gray-800 mb-3">Check your email</h2>
          <p className="text-sm text-gray-600 leading-relaxed mb-6">
            If an account exists for <span className="font-medium text-gray-800">{email}</span>,
            we've sent a password reset link. The link will expire in 1 hour.
          </p>
          <p className="text-xs text-gray-500 mb-6">
            Didn't receive the email? Check your spam folder or try again.
          </p>
          <div className="space-y-3">
            <Button
              variant="outline"
              fullWidth
              onClick={() => {
                setSubmitted(false);
                setEmail('');
              }}
            >
              Try another email
            </Button>
            <Link
              to="/login"
              className="block text-sm text-primary font-medium hover:underline"
            >
              Back to Sign In
            </Link>
          </div>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout>
      <h2 className="text-xl font-semibold text-gray-800 mb-2">Forgot your password?</h2>
      <p className="text-sm text-gray-500 mb-6">
        Enter your email address and we'll send you a link to reset your password.
      </p>

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

        <Button type="submit" fullWidth loading={isLoading}>
          Send Reset Link
        </Button>
      </form>

      <div className="mt-6 text-center text-sm text-gray-500">
        Remember your password?{' '}
        <Link to="/login" className="text-primary font-medium hover:underline">
          Sign in
        </Link>
      </div>
    </AuthLayout>
  );
}
