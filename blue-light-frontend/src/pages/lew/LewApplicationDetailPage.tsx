import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import adminApi from '../../api/adminApi';
import documentApi from '../../api/documentApi';
import lewReviewApi from '../../api/lewReviewApi';
import { STATUS_STEPS, getStatusStep } from '../../utils/applicationUtils';
import {
  deriveLewPrimaryAction,
  deriveLewHeaderSubtitle,
  type LewPrimaryActionGuards,
} from '../../utils/lewActionUtils';

import { AdminApplicationInfo } from '../admin/sections/AdminApplicationInfo';

import type { AdminApplication, DocumentRequest, SldRequest } from '../../types';

/**
 * LEW 전용 신청 진입(랜딩) 페이지
 * - URL: /lew/applications/:id
 * - 본 페이지는 "신청 메타 + Phase 1(CoF) 진입 CTA"만 담당
 * - Documents/LOA/SLD/Payment 등 상세 워크플로우는 /lew/applications/:id/review 에서 다룸
 *
 * Admin 페이지(AdminApplicationDetailPage)와 분리한 이유:
 * - 부제 카피("Admin view ...")가 LEW 컨텍스트에 부적합
 * - 사이드바의 Admin Actions 카드(승인/결제확인/처리시작 등)는 LEW가 호출 불가
 * - LEW 진입 CTA를 본문 중간이 아닌 본문 상단에 명시적으로 배치
 */
export default function LewApplicationDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();

  const [application, setApplication] = useState<AdminApplication | null>(null);
  const [documentRequests, setDocumentRequests] = useState<DocumentRequest[]>([]);
  const [sldRequest, setSldRequest] = useState<SldRequest | null>(null);
  const [loading, setLoading] = useState(true);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // PR3: "Request payment" CTA 흐름 — confirm dialog + 호출 진행 상태
  const [showRequestPaymentConfirm, setShowRequestPaymentConfirm] = useState(false);
  const [requestingPayment, setRequestingPayment] = useState(false);

  const applicationId = Number(id);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setErrorCode(null);
    setErrorMessage(null);
    try {
      // 핵심: Application은 필수. 가드 정보(서류 요청, SLD)는 부분 실패 허용 (allSettled).
      const appData = await adminApi.getApplication(applicationId);
      setApplication(appData);

      const [docsRes, sldRes] = await Promise.allSettled([
        documentApi.getDocumentRequests(applicationId),
        appData.sldOption === 'REQUEST_LEW'
          ? adminApi.getAdminSldRequest(applicationId)
          : Promise.resolve(null),
      ]);
      setDocumentRequests(docsRes.status === 'fulfilled' ? docsRes.value : []);
      setSldRequest(sldRes.status === 'fulfilled' ? sldRes.value : null);
    } catch (err: unknown) {
      // 백엔드가 APPLICATION_NOT_ASSIGNED 코드를 줄 수 있음
      const e = err as { response?: { data?: { code?: string; message?: string } }; message?: string };
      const code = e?.response?.data?.code ?? null;
      const message = e?.response?.data?.message ?? e?.message ?? 'Failed to load application';
      setErrorCode(code);
      setErrorMessage(message);
      if (!code) {
        toast.error('Failed to load application details');
      }
    } finally {
      setLoading(false);
    }
  }, [applicationId, toast]);

  /**
   * PR3: LEW가 결제 요청을 트리거.
   *
   * - 가드 위반 코드(KVA_NOT_CONFIRMED / DOCUMENT_REQUESTS_PENDING)는 toast + review 페이지로 이동 안내
   * - 성공 시 toast + 페이지 새로고침 (status가 PENDING_PAYMENT로 전이됨)
   */
  const handleRequestPayment = useCallback(async () => {
    setShowRequestPaymentConfirm(false);
    setRequestingPayment(true);
    try {
      await lewReviewApi.requestPayment(applicationId);
      toast.success('Payment requested. The applicant will be notified to pay the licence fee.');
      await fetchData();
    } catch (err: unknown) {
      const e = err as {
        response?: { data?: { code?: string; message?: string } };
        message?: string;
      };
      const code = e?.response?.data?.code ?? null;
      const message =
        e?.response?.data?.message ?? e?.message ?? 'Failed to request payment';
      const reviewUrl = `/lew/applications/${applicationId}/review`;
      switch (code) {
        case 'KVA_NOT_CONFIRMED':
          toast.error('kVA must be confirmed before requesting payment.');
          navigate(reviewUrl);
          break;
        case 'DOCUMENT_REQUESTS_PENDING':
          toast.error(
            'Resolve all pending document requests before requesting payment.',
          );
          navigate(reviewUrl);
          break;
        case 'INVALID_STATUS_TRANSITION':
          toast.warning(
            'This application is no longer in review — refreshing latest state.',
          );
          await fetchData();
          break;
        case 'APPLICATION_NOT_ASSIGNED':
          toast.error('You are no longer assigned to this application.');
          navigate('/lew/applications');
          break;
        default:
          toast.error(message);
      }
    } finally {
      setRequestingPayment(false);
    }
  }, [applicationId, fetchData, navigate, toast]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading application..." />
      </div>
    );
  }

  // LEW는 본인에게 배정된 신청만 열람 가능.
  // - 백엔드가 APPLICATION_NOT_ASSIGNED 코드를 주면 그 메시지를 우선 표시
  // - 데이터를 받았더라도 assignedLewSeq != currentUser.userSeq 이면 차단
  const isAuthorisedLew =
    currentUser?.role === 'LEW' &&
    !!application &&
    application.assignedLewSeq === currentUser.userSeq;

  if (errorCode === 'APPLICATION_NOT_ASSIGNED' || (!loading && application && !isAuthorisedLew)) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/lew/applications')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to applications list"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">LEW Review</h1>
        </div>
        <Card>
          <div className="flex items-start gap-3">
            <span className="text-lg" aria-hidden>🔒</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800">You are not assigned to this application</p>
              <p className="text-sm text-gray-600 mt-1">
                Only the LEW assigned to this application can view it. Please contact the administrator if you believe this is a mistake.
              </p>
              <div className="mt-3">
                <Button variant="outline" size="sm" onClick={() => navigate('/lew/applications')}>
                  Back to my applications
                </Button>
              </div>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  // 일반 에러 (load 실패) — application 자체가 없는 케이스
  if (!application) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/lew/applications')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to applications list"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">LEW Review</h1>
        </div>
        <Card>
          <div className="flex items-start gap-3">
            <span className="text-lg" aria-hidden>⚠️</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800">Unable to load application</p>
              <p className="text-sm text-gray-600 mt-1">
                {errorMessage || 'Something went wrong loading this application.'}
              </p>
              <div className="mt-3 flex gap-2">
                <Button variant="outline" size="sm" onClick={() => fetchData()}>
                  Retry
                </Button>
                <Button variant="ghost" size="sm" onClick={() => navigate('/lew/applications')}>
                  Back to list
                </Button>
              </div>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  // PR3: Phase 1 종료 가드 (DocumentRequest 미해결 0건 + kVA CONFIRMED) 충족 시 CTA가
  // "Start review" → "Request payment" 로 전환된다. 백엔드가 최종 가드를 재검증하므로
  // 프론트는 클릭 가능 여부만 결정.
  const pendingDocCount = documentRequests.filter(
    (d) => d.status === 'REQUESTED' || d.status === 'UPLOADED',
  ).length;
  const kvaConfirmed = application.kvaStatus === 'CONFIRMED';
  const sldRequired = application.sldOption === 'REQUEST_LEW';
  const sldReady = !sldRequired || sldRequest?.status === 'CONFIRMED';

  const guards: LewPrimaryActionGuards = {
    pendingDocCount,
    kvaConfirmed,
    sldRequired,
    sldReady,
  };

  const primaryAction = deriveLewPrimaryAction(application, guards);
  const headerSubtitle = deriveLewHeaderSubtitle(application.status);
  const showCtaCard = primaryAction.kind !== 'expired' && primaryAction.kind !== 'completed';

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/lew/applications')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to applications list"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <div>
            <div className="flex items-center gap-2">
              <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
                LEW Review &mdash; Application #{application.applicationSeq}
              </h1>
              <Badge variant={application.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
                {application.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
              </Badge>
            </div>
            <p className="text-sm text-gray-500 mt-0.5">{headerSubtitle}</p>
          </div>
        </div>
        <StatusBadge status={application.status} />
      </div>

      {/* Review Comment (admin이 남긴 review comment가 있다면 노출) */}
      {application.reviewComment && (
        <Card>
          <div className="flex items-start gap-3">
            <span className="text-lg">📝</span>
            <div className="flex-1">
              <p className="text-sm font-medium text-gray-800">Review Comment</p>
              <p className="text-sm text-gray-600 mt-1 whitespace-pre-wrap">{application.reviewComment}</p>
            </div>
          </div>
        </Card>
      )}

      {/* Mobile Progress */}
      <div className="lg:hidden">
        <Card>
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-800">Progress</h3>
            <StatusBadge status={application.status} />
          </div>
          {application.status !== 'EXPIRED' && (
            <div className="mt-3">
              <StepTracker steps={STATUS_STEPS} currentStep={getStatusStep(application.status)} variant="horizontal" />
            </div>
          )}
        </Card>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Main content */}
        <div className="lg:col-span-2 space-y-6">
          {/* 1차 CTA — status에서 파생 (lewActionUtils.deriveLewPrimaryAction)
              · PR2 범위: status만으로 분기. Phase 1 가드(pendingDocs/kvaConfirmed)에 의한
                "Request payment" 분기는 PR3에서 백엔드 endpoint와 함께 도입. */}
          {showCtaCard && (
            <Card>
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-gray-800">{primaryAction.label}</h3>
                  <p className="text-sm text-gray-600 mt-1">{primaryAction.description}</p>
                </div>
                <div className="flex-shrink-0">
                  <Button
                    variant="primary"
                    size="md"
                    disabled={primaryAction.disabled || requestingPayment}
                    onClick={() => {
                      if (primaryAction.disabled) return;
                      // PR3: Request payment 는 in-page 액션 — confirm dialog 후 POST 호출.
                      if (primaryAction.kind === 'requestPayment') {
                        setShowRequestPaymentConfirm(true);
                        return;
                      }
                      if (primaryAction.targetUrl) {
                        navigate(primaryAction.targetUrl);
                      }
                    }}
                  >
                    {requestingPayment && primaryAction.kind === 'requestPayment'
                      ? 'Requesting…'
                      : primaryAction.label}
                  </Button>
                </div>
              </div>
            </Card>
          )}

          {/* 신청 메타 정보 (Admin 페이지와 동일 컴포넌트 재사용) */}
          <AdminApplicationInfo
            application={application}
            onNavigateToOriginal={(seq) => navigate(`/lew/applications/${seq}`)}
          />
        </div>

        {/* Sidebar (LEW 전용 — Admin Actions / Assigned LEW 카드는 노출하지 않는다) */}
        <div className="space-y-6 lg:sticky lg:top-6 lg:self-start">
          {/* Progress (desktop) */}
          <div className="hidden lg:block">
            <Card>
              <h3 className="text-sm font-semibold text-gray-800 mb-4">Progress</h3>
              {application.status === 'EXPIRED' ? (
                <div className="text-center py-4">
                  <span className="text-3xl">⏰</span>
                  <p className="text-sm font-medium text-gray-700 mt-2">Application Expired</p>
                </div>
              ) : (
                <StepTracker
                  steps={STATUS_STEPS}
                  currentStep={getStatusStep(application.status)}
                  variant="vertical"
                />
              )}
            </Card>
          </div>

          {/* Quick Info (Documents/Payments 카운트는 PR1에서 제외 — LEW 권한 범위 미정) */}
          <Card>
            <h3 className="text-sm font-semibold text-gray-800 mb-3">Quick Info</h3>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-gray-500">Application ID</span>
                <span className="font-medium text-gray-700">#{application.applicationSeq}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Submitted</span>
                <span className="font-medium text-gray-700">
                  {new Date(application.createdAt).toLocaleDateString()}
                </span>
              </div>
              <div className="flex justify-between">
                <span className="text-gray-500">Last Updated</span>
                <span className="font-medium text-gray-700">
                  {new Date(application.updatedAt).toLocaleDateString()}
                </span>
              </div>
            </div>
          </Card>
        </div>
      </div>

      {/* PR3: Request payment confirm dialog */}
      <ConfirmDialog
        isOpen={showRequestPaymentConfirm}
        title="Request payment from applicant?"
        message={
          'The applicant will be notified to pay the licence fee. SLD, LOA, and the Certificate of Fitness will be completed after the payment is confirmed by admin.'
        }
        confirmLabel="Request payment"
        onConfirm={handleRequestPayment}
        onClose={() => setShowRequestPaymentConfirm(false)}
      />
    </div>
  );
}
