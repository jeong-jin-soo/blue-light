import { useState, useEffect, useCallback } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuthStore } from '../../stores/authStore';
import AuthLayout from '../../components/common/AuthLayout';
import { Input } from '../../components/ui/Input';
import { Button } from '../../components/ui/Button';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { authApi } from '../../api/authApi';

/**
 * Phase 1 (2026-04-17): 회원가입 간소화.
 * phone / companyName / uen / designation 4필드 제거.
 * 회사 정보는 ProfilePage에서 선택적으로 입력한다 (Just-in-Time Disclosure, AC-S1~S4).
 */
interface SignupForm {
  email: string;
  password: string;
  confirmPassword: string;
  firstName: string;
  lastName: string;
  role: string;
  lewLicenceNo: string;
  lewGrade: string;
  pdpaConsent: boolean;
}

const INITIAL_FORM: SignupForm = {
  email: '',
  password: '',
  confirmPassword: '',
  firstName: '',
  lastName: '',
  role: 'APPLICANT',
  lewLicenceNo: '',
  lewGrade: '',
  pdpaConsent: false,
};

export default function SignupPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { signup, isLoading, error, clearError, isAuthenticated, user } = useAuthStore();

  // Account type은 랜딩 페이지 링크의 ?role= 파라미터로 확정 (LEW | APPLICANT). 없으면 APPLICANT.
  const presetRole = searchParams.get('role');
  const initialRole = presetRole === 'LEW' ? 'LEW' : 'APPLICANT';

  const [form, setForm] = useState<SignupForm>({
    ...INITIAL_FORM,
    role: initialRole,
  });
  const [localError, setLocalError] = useState('');

  const [optionsLoading, setOptionsLoading] = useState(true);

  const updateField = useCallback(<K extends keyof SignupForm>(field: K, value: SignupForm[K]) => {
    setForm((prev) => ({ ...prev, [field]: value }));
  }, []);

  // LEW 가입 가능 여부 확인 — 불가능하면 APPLICANT로 폴백
  useEffect(() => {
    authApi.getSignupOptions()
      .then((options) => {
        if (presetRole === 'LEW' && !options.availableRoles.includes('LEW')) {
          updateField('role', 'APPLICANT');
        }
      })
      .catch(() => {
        // 실패 시 기본값(APPLICANT) 유지
      })
      .finally(() => setOptionsLoading(false));
  }, [presetRole, updateField]);

  useEffect(() => {
    if (isAuthenticated && user) {
      // 미승인 LEW는 대기 페이지로
      if (user.role === 'LEW' && !user.approved) {
        navigate('/lew-pending', { replace: true });
        return;
      }
      const dest = user.role === 'SYSTEM_ADMIN' ? '/admin/system' : user.role === 'ADMIN' ? '/admin/dashboard' : user.role === 'LEW' ? '/lew/dashboard' : '/dashboard';
      navigate(dest, { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    clearError();
    setLocalError('');

    if (form.password !== form.confirmPassword) {
      setLocalError('Passwords do not match');
      return;
    }

    if (form.password.length < 8) {
      setLocalError('Password must be at least 8 characters');
      return;
    }

    if (form.role === 'LEW' && !form.lewLicenceNo.trim()) {
      setLocalError('LEW licence number is required');
      return;
    }

    if (form.role === 'LEW' && !form.lewGrade) {
      setLocalError('LEW grade is required');
      return;
    }

    if (!form.pdpaConsent) {
      setLocalError('You must agree to the Privacy Policy to continue');
      return;
    }

    try {
      await signup({
        email: form.email,
        password: form.password,
        firstName: form.firstName,
        lastName: form.lastName,
        role: form.role,
        lewLicenceNo: form.role === 'LEW' ? form.lewLicenceNo.trim() : undefined,
        lewGrade: form.role === 'LEW' ? form.lewGrade : undefined,
        pdpaConsent: form.pdpaConsent,
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
      <h2 className="text-xl font-semibold text-gray-800 mb-1">
        {form.role === 'LEW' ? 'Register as LEW' : 'Create your account'}
      </h2>
      <p className="text-sm text-gray-500 mb-6">Get started in 30 seconds</p>

      {displayError && (
        <div className="mb-4 p-3 bg-error-50 border border-error-200 rounded-lg text-sm text-error-600">
          {displayError}
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="grid grid-cols-2 gap-3">
          <Input
            label="First Name"
            type="text"
            required
            maxLength={50}
            value={form.firstName}
            onChange={(e) => updateField('firstName', e.target.value)}
            placeholder="John"
          />
          <Input
            label="Last Name"
            type="text"
            required
            maxLength={50}
            value={form.lastName}
            onChange={(e) => updateField('lastName', e.target.value)}
            placeholder="Doe"
          />
        </div>

        <Input
          label="Email"
          type="email"
          required
          value={form.email}
          onChange={(e) => updateField('email', e.target.value)}
          placeholder="you@example.com"
        />

        {/* Role indicator — 랜딩 페이지 링크의 ?role= 로 확정 */}
        <div className="flex items-center gap-3 p-3 bg-primary/5 border border-primary/20 rounded-lg">
          <span className="text-lg">{form.role === 'LEW' ? '⚡' : '🏢'}</span>
          <div className="flex-1">
            <div className="text-sm font-medium text-primary">
              {form.role === 'LEW' ? 'LEW (Licensed Electrical Worker)' : 'Building Owner'}
            </div>
            <div className="text-xs text-gray-500">
              {form.role === 'LEW'
                ? 'Signing up as a Licensed Electrical Worker'
                : 'Signing up as a building / business / shop owner'}
            </div>
          </div>
        </div>

        {/* LEW additional fields */}
        {form.role === 'LEW' && (
          <div className="space-y-3">
            <p className="text-xs text-warning-600">
              ⚠ LEW accounts require administrator approval before access.
            </p>
            <Input
              label="LEW Licence Number"
              required
              maxLength={50}
              value={form.lewLicenceNo}
              onChange={(e) => updateField('lewLicenceNo', e.target.value)}
              placeholder="e.g., LEW-2026-XXXXX"
              hint="Your EMA-issued LEW licence number"
            />
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1.5">
                LEW Grade<span className="text-error-500 ml-0.5">*</span>
              </label>
              <div className="grid grid-cols-3 gap-2">
                {[
                  { value: 'GRADE_7', label: 'Grade 7', desc: '≤ 45 kVA' },
                  { value: 'GRADE_8', label: 'Grade 8', desc: '≤ 500 kVA' },
                  { value: 'GRADE_9', label: 'Grade 9', desc: '≤ 400 kV' },
                ].map((g) => (
                  <button
                    key={g.value}
                    type="button"
                    onClick={() => updateField('lewGrade', g.value)}
                    className={`p-2.5 border-2 rounded-lg text-center transition-all ${
                      form.lewGrade === g.value
                        ? 'border-primary bg-primary/5 text-primary'
                        : 'border-gray-200 bg-white text-gray-600 hover:border-gray-300'
                    }`}
                  >
                    <div className="text-sm font-medium">{g.label}</div>
                    <div className="text-xs text-gray-500 mt-0.5">{g.desc}</div>
                  </button>
                ))}
              </div>
              <p className="text-xs text-gray-500 mt-1">Select the grade on your EMA licence</p>
            </div>
          </div>
        )}

        <Input
          label="Password"
          type="password"
          required
          minLength={8}
          maxLength={20}
          value={form.password}
          onChange={(e) => updateField('password', e.target.value)}
          placeholder="8-20 characters"
          hint="Min 8 chars, 1 uppercase, 1 number"
        />

        <Input
          label="Confirm Password"
          type="password"
          required
          value={form.confirmPassword}
          onChange={(e) => updateField('confirmPassword', e.target.value)}
          placeholder="Re-enter your password"
        />

        {/* PDPA Consent */}
        <div className="flex items-start gap-2.5 pt-1">
          <input
            type="checkbox"
            id="pdpaConsent"
            checked={form.pdpaConsent}
            onChange={(e) => updateField('pdpaConsent', e.target.checked)}
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
            . I consent to the collection and use of my personal data under Singapore PDPA.
            <span className="text-error-500"> *</span>
          </label>
        </div>

        <Button type="submit" fullWidth loading={isLoading} className="mt-2">
          Create Account
        </Button>
      </form>

      <p className="text-xs text-gray-500 mt-6 text-center">
        You can add phone and company details later from your profile — they're optional.
      </p>

      <div className="mt-4 text-center text-sm text-gray-500">
        Already have an account?{' '}
        <Link to="/login" className="text-primary font-medium hover:underline">
          Sign in
        </Link>
      </div>
    </AuthLayout>
  );
}
