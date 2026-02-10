import { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { authApi } from '../../api/authApi';

export default function SignupPage() {
  const navigate = useNavigate();
  const { signup, isLoading, error, clearError, isAuthenticated, user } = useAuthStore();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [role, setRole] = useState('APPLICANT');
  const [lewLicenceNo, setLewLicenceNo] = useState('');
  const [companyName, setCompanyName] = useState('');
  const [uen, setUen] = useState('');
  const [designation, setDesignation] = useState('');
  const [pdpaConsent, setPdpaConsent] = useState(false);
  const [localError, setLocalError] = useState('');

  // 가입 가능한 역할 목록 (동적 로드)
  const [availableRoles, setAvailableRoles] = useState<string[]>([]);
  const [optionsLoading, setOptionsLoading] = useState(true);

  // 가입 옵션 로드
  useEffect(() => {
    authApi.getSignupOptions()
      .then((options) => {
        setAvailableRoles(options.availableRoles);
        // 역할이 1개뿐이면 자동 선택
        if (options.availableRoles.length === 1) {
          setRole(options.availableRoles[0]);
        }
      })
      .catch(() => {
        // 실패 시 기본값 (APPLICANT만)
        setAvailableRoles(['APPLICANT']);
      })
      .finally(() => setOptionsLoading(false));
  }, []);

  useEffect(() => {
    if (isAuthenticated && user) {
      // 미승인 LEW는 대기 페이지로
      if (user.role === 'LEW' && !user.approved) {
        navigate('/lew-pending', { replace: true });
        return;
      }
      const dest = user.role === 'ADMIN' || user.role === 'LEW' ? '/admin/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    clearError();
    setLocalError('');

    if (password !== confirmPassword) {
      setLocalError('Passwords do not match');
      return;
    }

    if (password.length < 8) {
      setLocalError('Password must be at least 8 characters');
      return;
    }

    if (role === 'LEW' && !lewLicenceNo.trim()) {
      setLocalError('LEW licence number is required');
      return;
    }

    if (!pdpaConsent) {
      setLocalError('You must agree to the Privacy Policy to continue');
      return;
    }

    try {
      await signup({
        email, password, name,
        phone: phone || undefined,
        role,
        lewLicenceNo: role === 'LEW' ? lewLicenceNo.trim() : undefined,
        companyName: role === 'APPLICANT' && companyName.trim() ? companyName.trim() : undefined,
        uen: role === 'APPLICANT' && uen.trim() ? uen.trim() : undefined,
        designation: role === 'APPLICANT' && designation.trim() ? designation.trim() : undefined,
        pdpaConsent,
      });
    } catch {
      // error is managed by store
    }
  };

  const displayError = localError || error;

  if (optionsLoading) {
    return (
      <AuthLayout>
        <div className="flex items-center justify-center py-12">
          <LoadingSpinner size="md" label="Loading..." />
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout>
      <h2 className="text-xl font-semibold text-gray-800 mb-6">Create your account</h2>

      {displayError && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg text-sm text-error-600">
          {displayError}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <Input
          label="Full Name"
          type="text"
          required
          maxLength={50}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="John Doe"
        />

        <Input
          label="Email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="you@example.com"
        />

        <Input
          label="Phone"
          type="tel"
          maxLength={20}
          value={phone}
          onChange={(e) => setPhone(e.target.value)}
          placeholder="+65-XXXX-XXXX"
          hint="Optional"
        />

        {/* Business Information — APPLICANT 회원가입 시 (선택적) */}
        {role === 'APPLICANT' && (
          <div className="space-y-3 p-3 bg-gray-50 rounded-lg border border-gray-200">
            <p className="text-xs font-medium text-gray-600">
              Business Information <span className="text-gray-400">(Optional — can be added later in Profile)</span>
            </p>
            <Input
              label="Company Name"
              maxLength={100}
              value={companyName}
              onChange={(e) => setCompanyName(e.target.value)}
              placeholder="e.g., BLUE LIGHT PTE LTD"
              hint="Will be printed on your installation licence"
            />
            <div className="grid grid-cols-2 gap-3">
              <Input
                label="UEN"
                maxLength={20}
                value={uen}
                onChange={(e) => setUen(e.target.value)}
                placeholder="e.g., 202407291M"
                hint="Business registration number"
              />
              <Input
                label="Designation"
                maxLength={50}
                value={designation}
                onChange={(e) => setDesignation(e.target.value)}
                placeholder="e.g., Director"
              />
            </div>
          </div>
        )}

        {/* Role selection — 역할이 2개 이상일 때만 표시 */}
        {availableRoles.length > 1 && (
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1.5">
              Account Type<span className="text-error-500 ml-0.5">*</span>
            </label>
            <div className="grid grid-cols-2 gap-3">
              <button
                type="button"
                onClick={() => setRole('APPLICANT')}
                className={`p-3 border-2 rounded-lg text-center transition-all ${
                  role === 'APPLICANT'
                    ? 'border-primary bg-primary/5 text-primary'
                    : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300'
                }`}
              >
                <div className="text-lg mb-1">🏢</div>
                <div className="text-sm font-medium">Building Owner</div>
                <div className="text-xs text-gray-500 mt-0.5">Applicant</div>
              </button>
              {availableRoles.includes('LEW') && (
                <button
                  type="button"
                  onClick={() => setRole('LEW')}
                  className={`p-3 border-2 rounded-lg text-center transition-all ${
                    role === 'LEW'
                      ? 'border-primary bg-primary/5 text-primary'
                      : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300'
                  }`}
                >
                  <div className="text-lg mb-1">⚡</div>
                  <div className="text-sm font-medium">LEW</div>
                  <div className="text-xs text-gray-500 mt-0.5">Licensed Electrical Worker</div>
                </button>
              )}
            </div>
            {role === 'LEW' && (
              <>
                <p className="text-xs text-warning-600 mt-1.5">
                  ⚠ LEW accounts require administrator approval before access.
                </p>
                <div className="mt-3">
                  <Input
                    label="LEW Licence Number"
                    required
                    maxLength={50}
                    value={lewLicenceNo}
                    onChange={(e) => setLewLicenceNo(e.target.value)}
                    placeholder="e.g., LEW-2026-XXXXX"
                    hint="Your EMA-issued LEW licence number"
                  />
                </div>
              </>
            )}
          </div>
        )}

        <Input
          label="Password"
          type="password"
          required
          minLength={8}
          maxLength={20}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="8-20 characters"
        />

        <Input
          label="Confirm Password"
          type="password"
          required
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          placeholder="Re-enter your password"
        />

        {/* PDPA Consent */}
        <div className="flex items-start gap-2.5 pt-1">
          <input
            type="checkbox"
            id="pdpaConsent"
            checked={pdpaConsent}
            onChange={(e) => setPdpaConsent(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary focus:ring-primary cursor-pointer"
          />
          <label htmlFor="pdpaConsent" className="text-xs text-gray-600 leading-relaxed cursor-pointer">
            I agree to the{' '}
            <a href="/privacy" target="_blank" className="text-primary font-medium hover:underline">
              Privacy Policy
            </a>{' '}
            and{' '}
            <a href="/disclaimer" target="_blank" className="text-primary font-medium hover:underline">
              Disclaimer
            </a>
            . I consent to the collection and use of my personal data as described.
            <span className="text-error-500"> *</span>
          </label>
        </div>

        <Button type="submit" fullWidth loading={isLoading} className="mt-2">
          Create Account
        </Button>
      </form>

      <div className="mt-6 text-center text-sm text-gray-500">
        Already have an account?{' '}
        <Link to="/login" className="text-primary font-medium hover:underline">
          Sign in
        </Link>
      </div>
    </AuthLayout>
  );
}
