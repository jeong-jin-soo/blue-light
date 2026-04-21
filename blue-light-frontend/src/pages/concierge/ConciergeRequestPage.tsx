/**
 * ConciergeRequestPage
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage B
 * - Public 진입 — 인증 불필요. /concierge/request
 * - 5종 동의 체크 + 이름/이메일/모바일/메모 → POST /api/public/concierge/request
 * - 성공 시 /concierge/request/success로 이동 (response state 전달)
 */

import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { AxiosError } from 'axios';
import axiosClient from '../../api/axiosClient';
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
  const [paymentInfo, setPaymentInfo] = useState<Record<string, string>>({});

  // 결제 QR·PayNow 정보 로드 (public, 인증 불필요)
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const { data } = await axiosClient.get<Record<string, string>>('/public/payment-info');
        if (!cancelled) setPaymentInfo(data || {});
      } catch {
        /* silent — 아래 UI에서 fallback 처리 */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

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
            {/* Payment — PayNow QR + 계좌 정보 (PG 연동은 Phase 2) */}
            <div className="border border-gray-200 rounded-lg p-4 bg-surface-secondary">
              <div className="flex items-center gap-2 mb-3">
                <span aria-hidden>💳</span>
                <span className="text-sm font-semibold text-gray-800">
                  Payment — PayNow
                </span>
              </div>
              <p className="text-xs text-gray-600 mb-3">
                Please complete payment via PayNow after submitting your request.
                Your Concierge Manager will confirm the exact service fee when
                contacting you.
              </p>

              {paymentInfo.payment_paynow_qr && (
                <div className="flex justify-center mb-3">
                  <img
                    src={`${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8090/api'}${paymentInfo.payment_paynow_qr}`}
                    alt="PayNow QR Code"
                    className="w-44 h-44 object-contain border border-gray-200 rounded-lg bg-white p-2"
                  />
                </div>
              )}

              {(paymentInfo.payment_paynow_uen || paymentInfo.payment_paynow_name) && (
                <div className="flex items-start gap-3">
                  <div className="w-8 h-8 bg-primary-100 rounded-lg flex items-center justify-center flex-shrink-0">
                    <span className="text-sm font-bold text-primary-700">P</span>
                  </div>
                  <div className="text-xs text-gray-700 space-y-0.5">
                    <p className="text-sm font-medium text-gray-800">PayNow (QR / UEN Transfer)</p>
                    {paymentInfo.payment_paynow_uen && (
                      <p>
                        UEN: <span className="font-mono font-medium">{paymentInfo.payment_paynow_uen}</span>
                      </p>
                    )}
                    {paymentInfo.payment_paynow_name && (
                      <p>
                        Name: <span className="font-medium">{paymentInfo.payment_paynow_name}</span>
                      </p>
                    )}
                    <p className="text-gray-500">
                      Reference: use your email address
                      {email.trim() && (
                        <> (<span className="font-mono">{email.trim()}</span>)</>
                      )}
                    </p>
                  </div>
                </div>
              )}

              {!paymentInfo.payment_paynow_qr
                && !paymentInfo.payment_paynow_uen
                && !paymentInfo.payment_paynow_name && (
                <p className="text-xs text-gray-500 italic">
                  Payment details will be shared by your Concierge Manager after
                  you submit this request.
                </p>
              )}
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
