/**
 * ConciergeRequestPage
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage B
 * - Public 진입 — 인증 불필요. /concierge/request
 * - 5종 동의 체크 + 이름/이메일/모바일/메모 → POST /api/public/concierge/request
 * - 성공 시 /concierge/request/success로 이동 (response state 전달)
 */

import { useMemo, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Card } from '../../components/ui/Card';
import { ConsentChecklist } from '../../components/consent/ConsentChecklist';
import {
  CONCIERGE_CONSENT_ITEMS,
  CONCIERGE_TERMS_VERSION,
} from '../../constants/conciergeConsent';
import { submitConciergeRequest } from '../../api/conciergeApi';
import type {
  ConciergeRequestCreatePayload,
} from '../../api/conciergeApi';
import type { ApiError } from '../../types';

interface ConsentValues {
  pdpa: boolean;
  terms: boolean;
  signup: boolean;
  delegation: boolean;
  marketing: boolean;
  [key: string]: boolean;
}

const INITIAL_CONSENTS: ConsentValues = {
  pdpa: false,
  terms: false,
  signup: false,
  delegation: false,
  marketing: false,
};

export default function ConciergeRequestPage() {
  const navigate = useNavigate();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [memo, setMemo] = useState('');
  const [consents, setConsents] = useState<ConsentValues>(INITIAL_CONSENTS);

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const allRequiredChecked = useMemo(
    () =>
      CONCIERGE_CONSENT_ITEMS.filter((i) => i.required).every(
        (i) => consents[i.key]
      ),
    [consents]
  );

  const anyConsentTouched = useMemo(
    () => Object.values(consents).some(Boolean),
    [consents]
  );

  const canSubmit =
    fullName.trim().length > 0 &&
    email.trim().length > 0 &&
    mobile.trim().length > 0 &&
    allRequiredChecked &&
    !submitting;

  const handleConsentChange = (key: string, checked: boolean) => {
    setConsents((prev) => ({ ...prev, [key]: checked }));
  };

  const handleChangeAll = (checked: boolean) => {
    setConsents({
      pdpa: checked,
      terms: checked,
      signup: checked,
      delegation: checked,
      marketing: checked,
    });
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setFieldErrors({});
    setSubmitting(true);

    const payload: ConciergeRequestCreatePayload = {
      fullName: fullName.trim(),
      email: email.trim(),
      mobileNumber: mobile.trim(),
      memo: memo.trim() || undefined,
      pdpaConsent: consents.pdpa,
      termsAgreed: consents.terms,
      signupConsent: consents.signup,
      delegationConsent: consents.delegation,
      marketingOptIn: consents.marketing,
      termsVersion: CONCIERGE_TERMS_VERSION,
    };

    try {
      const result = await submitConciergeRequest(payload);
      navigate('/concierge/request/success', {
        state: {
          publicCode: result.publicCode,
          existingUser: result.existingUser,
          accountSetupRequired: result.accountSetupRequired,
          message: result.message,
          email: payload.email,
        },
      });
    } catch (err) {
      if (err instanceof AxiosError) {
        const status = err.response?.status;
        const data = err.response?.data as ApiError | undefined;
        const code = data?.code;

        if (status === 409 && code === 'ACCOUNT_NOT_ELIGIBLE') {
          setFormError(
            'This email cannot be registered for concierge service. Please contact support at support@licensekaki.sg.'
          );
        } else if (status === 422 && code === 'STAFF_EMAIL_NOT_ALLOWED') {
          setFormError(
            'Staff accounts cannot use concierge service. Please log in with your existing account.'
          );
        } else if (status === 400 && data?.details) {
          setFieldErrors(data.details);
          setFormError('Please fix the highlighted fields.');
        } else {
          setFormError(
            data?.message ||
              (err as { message?: string }).message ||
              'Something went wrong. Please try again.'
          );
        }
      } else if (
        err &&
        typeof err === 'object' &&
        'message' in err &&
        typeof (err as { message: unknown }).message === 'string'
      ) {
        // axiosClient interceptor가 정규화한 에러 객체 (code/message 보존)
        setFormError((err as { message: string }).message);
      } else {
        setFormError('Network error. Please check your connection.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-200">
        <div className="max-w-3xl mx-auto px-4 sm:px-6 py-4 flex items-center justify-between">
          <button
            type="button"
            onClick={() => navigate('/')}
            className="text-gray-600 hover:text-gray-900 text-sm flex items-center gap-1"
          >
            ← Back
          </button>
          <span className="text-sm font-semibold text-gray-900">LicenseKaki</span>
          <span className="w-12" aria-hidden="true" />
        </div>
      </header>

      <main className="max-w-3xl mx-auto px-4 sm:px-6 py-8 sm:py-12">
        <div className="text-center mb-8">
          <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-concierge-100 text-concierge-700 text-xs font-semibold mb-3">
            White-Glove Service
          </div>
          <h1 className="text-2xl sm:text-3xl font-bold text-gray-900 mb-2">
            Kaki Concierge Service
          </h1>
          <p className="text-gray-700 max-w-xl mx-auto">
            Our team personally manages your entire electrical licensing process —
            from submission to approval. Submit the form below and a Concierge
            Manager will contact you within 24 hours.
          </p>
        </div>

        <Card padding="lg">
          <form onSubmit={handleSubmit} className="space-y-5" noValidate>
            <Input
              label="Full name"
              name="fullName"
              required
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              error={fieldErrors.fullName}
              autoComplete="name"
              maxLength={100}
              disabled={submitting}
            />
            <Input
              label="Email"
              name="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={fieldErrors.email}
              hint="We'll send you an account setup link at this address."
              autoComplete="email"
              maxLength={100}
              disabled={submitting}
            />
            <Input
              label="Mobile number"
              name="mobileNumber"
              type="tel"
              required
              value={mobile}
              onChange={(e) => setMobile(e.target.value)}
              error={fieldErrors.mobileNumber}
              hint="Include country code, e.g. +65 9123 4567"
              autoComplete="tel"
              maxLength={20}
              disabled={submitting}
            />

            {/* Memo (optional) */}
            <div>
              <label
                htmlFor="memo"
                className="block text-sm font-medium text-gray-700 mb-1.5"
              >
                Note <span className="text-gray-500 font-normal">(optional)</span>
              </label>
              <textarea
                id="memo"
                name="memo"
                value={memo}
                onChange={(e) => setMemo(e.target.value)}
                rows={3}
                maxLength={2000}
                disabled={submitting}
                placeholder="Any context you'd like our team to know (optional)."
                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg text-sm placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary"
              />
              <p className="mt-1 text-xs text-gray-500">{memo.length}/2000</p>
            </div>

            <ConsentChecklist
              items={CONCIERGE_CONSENT_ITEMS}
              values={consents}
              onChange={handleConsentChange}
              onChangeAll={handleChangeAll}
              disabled={submitting}
              error={
                anyConsentTouched && !allRequiredChecked
                  ? 'Please check all required consents to submit.'
                  : undefined
              }
            />

            {/* Payment placeholder (Phase 2) */}
            <div className="border border-dashed border-gray-300 rounded-lg p-4 bg-gray-50 text-center">
              <p className="text-sm text-gray-600">
                Payment will be collected at the next step.
                <span className="block text-xs text-gray-500 mt-1">
                  No charge will be made until you explicitly confirm.
                </span>
              </p>
            </div>

            {formError && (
              <div
                role="alert"
                className="p-3 rounded-md bg-red-50 border border-red-200 text-sm text-red-800"
              >
                {formError}
              </div>
            )}

            <Button
              type="submit"
              variant="concierge"
              size="lg"
              fullWidth
              disabled={!canSubmit}
              loading={submitting}
            >
              Submit concierge request
            </Button>

            <p className="text-xs text-gray-500 text-center">
              By submitting, you acknowledge the required consents above. A manager
              will contact you within 24 hours.
            </p>
          </form>
        </Card>
      </main>
    </div>
  );
}
