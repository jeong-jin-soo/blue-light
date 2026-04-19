/**
 * ConciergeRequestSuccessPage
 * - Kaki Concierge v1.5 Phase 1 PR#3 Stage B
 * - /concierge/request/success (Public)
 * - location.state로 응답을 받아 분기 표시 (C1/C3 → Activation 안내, C2 → Login 안내).
 * - state가 없으면 홈으로 redirect (직접 URL 접근 방지).
 */

import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';

interface LocationState {
  publicCode: string;
  existingUser: boolean;
  accountSetupRequired: boolean;
  message: string;
  email: string;
}

export default function ConciergeRequestSuccessPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = location.state as LocationState | null;

  if (!state) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      <main className="flex-1 max-w-2xl mx-auto px-4 sm:px-6 py-12 w-full">
        <Card padding="lg">
          <div className="text-center mb-8">
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-success-100 flex items-center justify-center">
              <svg
                aria-hidden="true"
                className="w-10 h-10 text-success-600"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.5"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            </div>
            <h1 className="text-2xl font-bold text-gray-900 mb-2">
              Request received!
            </h1>
            <p className="text-gray-700">{state.message}</p>
          </div>

          <div className="bg-gray-50 rounded-lg p-4 mb-6">
            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-gray-600">Reference</dt>
                <dd className="font-mono font-semibold text-gray-900">
                  {state.publicCode}
                </dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-600">Email</dt>
                <dd className="text-gray-900 break-all">{state.email}</dd>
              </div>
            </dl>
          </div>

          {state.accountSetupRequired ? (
            <div className="border-2 border-concierge-300 rounded-lg p-4 mb-6 bg-concierge-50">
              <h2 className="text-sm font-semibold text-concierge-800 mb-2">
                Next step: Activate your account
              </h2>
              <p className="text-sm text-gray-800">
                We sent an account setup link to{' '}
                <strong className="break-all">{state.email}</strong>. Your account
                is currently <strong>inactive</strong> — click the link in the
                email within 48 hours to set your password and activate your
                account.
              </p>
            </div>
          ) : (
            <div className="border border-gray-200 rounded-lg p-4 mb-6 bg-gray-50">
              <h2 className="text-sm font-semibold text-gray-800 mb-2">
                Your existing account is linked
              </h2>
              <p className="text-sm text-gray-700">
                This concierge request has been linked to your existing LicenseKaki
                account. You can log in to track progress.
              </p>
              <Button
                variant="primary"
                size="sm"
                className="mt-3"
                onClick={() => navigate('/login')}
              >
                Go to login
              </Button>
            </div>
          )}

          <div className="border-t border-gray-200 pt-4 text-sm text-gray-600 space-y-2">
            <p>
              <strong>What happens next?</strong> A Concierge Manager will contact
              you within 24 hours.
            </p>
            <p className="text-xs text-gray-500">
              Didn&apos;t submit this request? Contact us at{' '}
              <a
                href="mailto:support@licensekaki.sg"
                className="text-primary-700 hover:underline"
              >
                support@licensekaki.sg
              </a>
              .
            </p>
          </div>

          <div className="mt-6 text-center">
            <button
              type="button"
              onClick={() => navigate('/')}
              className="text-sm text-gray-600 hover:text-gray-900"
            >
              ← Back to home
            </button>
          </div>
        </Card>
      </main>
    </div>
  );
}
