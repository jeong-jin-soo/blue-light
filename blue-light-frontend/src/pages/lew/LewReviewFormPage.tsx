import { useCallback, useEffect, useMemo, useState } from 'react';
import type { AxiosError } from 'axios';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { InfoBox } from '../../components/ui/InfoBox';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { PageHeader } from '../../components/ui/PageHeader';
import { Tabs, TabPanel, type TabDefinition } from '../../components/ui/Tabs';
import { KvaSection } from '../../components/admin/KvaSection';
import { AdminSldSection } from '../admin/sections/AdminSldSection';
import { LewLoaExchangeSection } from './sections/LewLoaExchangeSection';
import { LewConfirmationSummary } from './sections/LewConfirmationSummary';
import { AdminEmaSection } from '../admin/sections/AdminEmaSection';
import { CompleteModal } from '../admin/sections/AdminModals';
import { LewDocumentReviewSection } from '../../components/document/LewDocumentReviewSection';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { useEmaActions } from '../../hooks/useEmaActions';
import { formatEmaStatus, getEmaStatusBadge } from '../../utils/applicationUtils';
import lewReviewApi from '../../api/lewReviewApi';
import adminApi from '../../api/adminApi';
import fileApi from '../../api/fileApi';
import loaApi from '../../api/loaApi';
import documentApi from '../../api/documentApi';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import { useRequestPayment } from '../../hooks/useRequestPayment';
import type { LewApplicationResponse } from '../../types/cof';
import type {
  AdminApplication,
  DocumentRequest,
  FileInfo,
  LoaStatus,
  SldRequest,
} from '../../types';
import { AdminApplicationInfo } from '../admin/sections/AdminApplicationInfo';

/**
 * LEW 통합 리뷰 페이지.
 *
 * URL: `/lew/applications/:id/review`
 * 권한: LEW 역할만 (ProtectedRoute). 배정 여부는 백엔드 `@appSec.isAssignedLew`가 최종 판정.
 *
 * <h3>탭</h3>
 * <ol>
 *   <li>Documents — LEW가 신청자에게 서류 요청·검토</li>
 *   <li>kVA — LEW 확정 (Application.selectedKva SSOT)</li>
 *   <li>SLD — sldOption=REQUEST_LEW 일 때만 노출</li>
 *   <li>LOA — 생성/업로드 (동선 재설계 A: LEW 검토 흐름에 개방)</li>
 * </ol>
 *
 * <p>결제 요청(request-payment) 가드 = Phase 1 (kVA 확정 + 미해결 서류 0건).</p>
 */

type TabKey = 'documents' | 'kva' | 'sld' | 'loa' | 'ema';

type ApiErrorShape = AxiosError<{ code?: string; message?: string }> & {
  code?: string;
  message?: string;
};

export default function LewReviewFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<{ code: string; message: string } | null>(null);

  // LEW 전용 응답 (hint + MSSL 평문 + 평문 주소)
  const [lewData, setLewData] = useState<LewApplicationResponse | null>(null);
  // /api/admin/applications/{id} — KvaSection/LOA/SLD 모두 이 형상 요구
  const [adminApp, setAdminApp] = useState<AdminApplication | null>(null);
  const [loaStatus, setLoaStatus] = useState<LoaStatus | null>(null);
  const [loaGenerating, setLoaGenerating] = useState(false);
  const [loaUploading, setLoaUploading] = useState(false);
  const [sldRequest, setSldRequest] = useState<SldRequest | null>(null);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [documentRequests, setDocumentRequests] = useState<DocumentRequest[]>([]);
  const [sldLewNote, setSldLewNote] = useState('');
  const [showSldConfirm, setShowSldConfirm] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);

  // 기본 활성 탭은 데이터 로드 후 useEffect에서 "첫 미완료 탭"으로 동적 설정한다.
  const [activeTab, setActiveTab] = useState<TabKey | null>(null);

  // 옵션 B — 신청 정보 시야 토글.
  const [appInfoOpen, setAppInfoOpen] = useState(false);

  const applicationId = id ? Number(id) : NaN;
  const idValid = Number.isFinite(applicationId) && applicationId > 0;

  // ── EMA 제출 추적 (ema-submission-tracking-spec.md §8) ──
  const ema = useEmaActions(applicationId);
  const [showCompleteModal, setShowCompleteModal] = useState(false);
  const [completeForm, setCompleteForm] = useState({ licenseNumber: '', licenseExpiryDate: '' });
  const [completing, setCompleting] = useState(false);
  const [startingProcessing, setStartingProcessing] = useState(false);

  // ── Fetch ────────────────────────────────────────────
  const loadData = useCallback(async () => {
    if (!idValid) return;
    setLoading((prev) => (lewData ? prev : true));
    setLoadError(null);
    try {
      // 핵심: LEW 응답 + Admin Application 응답은 필수. 나머지는 allSettled 로 부분 실패 허용.
      const [lewRes, adminRes] = await Promise.all([
        lewReviewApi.getAssignedApplication(applicationId),
        adminApi.getApplication(applicationId),
      ]);
      setLewData(lewRes);
      setAdminApp(adminRes);

      const [loaRes, sldRes, filesRes, docsRes] = await Promise.allSettled([
        loaApi.getLoaStatus(applicationId),
        adminRes.sldOption === 'REQUEST_LEW'
          ? adminApi.getAdminSldRequest(applicationId)
          : Promise.resolve(null),
        fileApi.getFilesByApplication(applicationId),
        documentApi.getDocumentRequests(applicationId),
      ]);
      setLoaStatus(loaRes.status === 'fulfilled' ? loaRes.value : null);
      setSldRequest(sldRes.status === 'fulfilled' ? sldRes.value : null);
      setFiles(filesRes.status === 'fulfilled' ? filesRes.value : []);
      setDocumentRequests(docsRes.status === 'fulfilled' ? docsRes.value : []);
    } catch (err) {
      const e = err as ApiErrorShape;
      const code =
        (e as unknown as { code?: string }).code ||
        e.response?.data?.code ||
        'UNKNOWN';
      const message =
        (e as unknown as { message?: string }).message ||
        e.response?.data?.message ||
        'Failed to load application';
      setLoadError({ code, message });
    } finally {
      setLoading(false);
    }
  }, [applicationId, idValid, lewData]);

  useEffect(() => {
    if (!idValid) {
      setLoadError({ code: 'INVALID_ID', message: 'Invalid application id' });
      setLoading(false);
      return;
    }
    void loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idValid, applicationId]);

  // ── LoA 교환 모델 액션 (loa-exchange-redesign-spec.md §3.3, PR3b) ──
  // send-form / final-upload 는 /api/lew/** (담당 LEW 전용) 경로 사용.
  // (loaGenerating/loaUploading state 는 각각 sending/uploading 의미로 재사용)
  const handleSendLoaForm = async () => {
    setLoaGenerating(true);
    try {
      const status = await loaApi.sendLoaForm(applicationId);
      setLoaStatus(status);
      toast.success('LoA form sent to applicant');
    } catch (err) {
      const code = (err as ApiErrorShape).response?.data?.code;
      if (code === 'NO_ACTIVE_LOA_FORM') {
        toast.error('No active LoA form is configured. Please contact an administrator.');
      } else {
        toast.error('Failed to send LoA form');
      }
    } finally {
      setLoaGenerating(false);
    }
  };
  const handleUploadFinalLoa = async (file: File) => {
    setLoaUploading(true);
    try {
      const status = await loaApi.uploadFinalLoa(applicationId, file);
      setLoaStatus(status);
      toast.success('Final LoA uploaded');
    } catch {
      toast.error('Failed to upload final LoA');
    } finally {
      setLoaUploading(false);
    }
  };
  const handleLoaDownload = async (fileSeq: number, filename: string) => {
    try { await fileApi.downloadFile(fileSeq, filename); }
    catch { toast.error('Failed to download LoA'); }
  };

  // ── Derived / Guards ─────────────────────────────────
  const pendingDocCount = useMemo(
    () => documentRequests.filter((d) => d.status === 'REQUESTED' || d.status === 'UPLOADED').length,
    [documentRequests],
  );
  const kvaConfirmed = adminApp?.kvaStatus === 'CONFIRMED';
  // 신청자 입력값(USER_INPUT)은 CONFIRMED 라도 LEW 검토 전 — 배지로 "검토 필요" 구분.
  const kvaLewVerified = kvaConfirmed && adminApp?.kvaSource === 'LEW_VERIFIED';
  const sldRequired = adminApp?.sldOption === 'REQUEST_LEW';
  const sldReady = !sldRequired || sldRequest?.status === 'CONFIRMED';

  // ── 결제 요청 (Phase 1 액션) ──────
  // 결제 요청 가드 = kVA 확정뿐 (2026-06-18 결정, payment-gateway-marketplace-spec.md §1.5):
  // kVA 확정이 "필요 정보 수취 완료" 신호 → 미해결 문서요청·LoA·SLD 는 결제를 막지 않고 병렬 진행.
  // (LoA 최종본은 작업개시 게이트로만 유지). status 가 PENDING_REVIEW/REVISION_REQUESTED 일 때만 노출.
  // 백엔드 LewReviewService.requestPayment 가드와 일치.
  const appStatus = adminApp?.status;
  const inPhase1 = appStatus === 'PENDING_REVIEW' || appStatus === 'REVISION_REQUESTED';
  // 완료/만료 = 읽기 전용. 리뷰 화면은 어느 단계든 열람 가능하되, 이 상태에서는 편집 동선을 잠근다.
  const isTerminal = appStatus === 'COMPLETED' || appStatus === 'EXPIRED';
  // 결제 요청 가드 = kVA 확정뿐 (2026-06-18). 문서요청·LoA 는 결제 전제가 아니라 병렬 진행.
  const phase1Ready = kvaConfirmed;
  const [showRequestPaymentConfirm, setShowRequestPaymentConfirm] = useState(false);
  const { run: runRequestPayment, requesting: requestingPayment } = useRequestPayment(
    applicationId,
    {
      onSuccess: loadData,
      onStaleState: loadData,
      // 가드 위반(kVA/서류/LoA) 시 이미 리뷰 폼이므로 해당 탭으로 점프.
      onNeedsReview: (reason) =>
        setActiveTab(reason === 'kva' ? 'kva' : reason === 'loa' ? 'loa' : 'documents'),
    },
  );

  // 기본 활성 탭 — 알림 딥링크 해시(#documents/#kva/#sld/#loa/#ema)가 있으면 해당 탭 선택,
  // 없으면 Documents 로 시작. 사용자가 이미 탭을 직접 선택했다면 그 선택을 존중.
  useEffect(() => {
    if (activeTab !== null) return;
    if (!adminApp || !lewData) return;
    const hash = location.hash.slice(1);
    const valid: TabKey[] = ['documents', 'kva', 'sld', 'loa', 'ema'];
    setActiveTab((valid as string[]).includes(hash) ? (hash as TabKey) : 'documents');
  }, [activeTab, adminApp, lewData, location.hash]);

  // EMA 상태 로드 — IN_PROGRESS 진입 이후 의미가 있으나, 탭은 항상 보이므로 status 가 있으면 로드.
  // (백엔드 GET /ema 는 IN_PROGRESS 전에도 NOT_SUBMITTED 응답을 준다 → 탭 비활성 안내에 사용.)
  const emaRefresh = ema.refresh;
  useEffect(() => {
    if (!adminApp) return;
    void emaRefresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [adminApp?.applicationSeq, adminApp?.status]);

  // Complete & Issue Licence — 게이트(ema=APPROVED + LICENSE_PDF)는 백엔드가 강제.
  const handleComplete = useCallback(async () => {
    setCompleting(true);
    try {
      await adminApi.completeApplication(applicationId, completeForm);
      toast.success('Licence issued. The application is now complete.');
      setShowCompleteModal(false);
      setCompleteForm({ licenseNumber: '', licenseExpiryDate: '' });
      await loadData();
      await ema.refresh();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { code?: string; message?: string } } };
      const code = e?.response?.data?.code;
      if (code === 'EMA_NOT_APPROVED') toast.error('EMA submission must be approved before completion.');
      else if (code === 'LICENSE_PDF_MISSING') toast.error('Upload the licence PDF before completing.');
      else toast.error(e?.response?.data?.message || 'Failed to complete the application');
    } finally {
      setCompleting(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationId, completeForm, loadData, toast]);

  // Start processing — PAID → IN_PROGRESS. EMA 작업은 IN_PROGRESS 에서만 가능하므로
  // LEW 가 최종 LoA 업로드 후 직접 처리 시작할 수 있게 한다. LOA_FINAL 게이트는 백엔드가 강제.
  const handleStartProcessing = useCallback(async () => {
    setStartingProcessing(true);
    try {
      await adminApi.updateStatus(applicationId, { status: 'IN_PROGRESS' });
      toast.success('Processing started. You can now submit to EMA ELISE.');
      await loadData();
      await ema.refresh();
    } catch (err: unknown) {
      const e = err as { response?: { data?: { code?: string; message?: string } } };
      if (e?.response?.data?.code === 'LOA_FINAL_NOT_UPLOADED') {
        toast.error('Upload the final LoA in the LOA tab before starting processing.');
      } else {
        toast.error(e?.response?.data?.message || 'Failed to start processing');
      }
    } finally {
      setStartingProcessing(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [applicationId, loadData, toast]);

  // Phase 3 권한: LEW는 assigned_lew_seq 일치 시만 서류 요청 가능. 완료/만료 후에는 잠금.
  const canRequestDocuments =
    !isTerminal &&
    currentUser?.role === 'LEW' &&
    !!adminApp?.assignedLewSeq &&
    adminApp.assignedLewSeq === currentUser?.userSeq;

  // ── SLD handlers (AdminSldSection 용) ─────────────────
  const handleSldUpload = useCallback(async (file: File) => {
    const uploaded = await adminApi.uploadFile(applicationId, file, 'DRAWING_SLD');
    await adminApi.uploadSldComplete(applicationId, uploaded.fileSeq, sldLewNote || undefined);
    toast.success('SLD uploaded and marked as complete');
    setSldLewNote('');
    await loadData();
  }, [applicationId, loadData, sldLewNote, toast]);

  const handleSldConfirm = useCallback(async () => {
    setShowSldConfirm(false);
    setActionLoading(true);
    try {
      await adminApi.confirmSld(applicationId);
      toast.success('SLD confirmed');
      await loadData();
    } catch {
      toast.error('Failed to confirm SLD');
    } finally {
      setActionLoading(false);
    }
  }, [applicationId, loadData, toast]);

  const handleSldUnconfirm = useCallback(async () => {
    if (!confirm('Reopen the SLD? This will allow re-uploading or regenerating the SLD drawing.')) return;
    setActionLoading(true);
    try {
      await adminApi.unconfirmSld(applicationId);
      toast.success('SLD reopened for editing');
      await loadData();
    } catch {
      toast.error('Failed to reopen SLD');
    } finally {
      setActionLoading(false);
    }
  }, [applicationId, loadData, toast]);

  const handleFileDelete = useCallback(async (fileId: number) => {
    if (!confirm('Are you sure you want to delete this file?')) return;
    try {
      await fileApi.deleteFile(fileId);
      toast.success('File deleted');
      await loadData();
    } catch {
      toast.error('Failed to delete file');
    }
  }, [loadData, toast]);

  // ── Render ────────────────────────────────────────────
  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading application…" />
      </div>
    );
  }

  if (loadError || !lewData || !adminApp) {
    return (
      <ErrorPanel
        code={loadError?.code ?? 'UNKNOWN'}
        message={loadError?.message ?? 'Failed to load application'}
        onBack={() => navigate('/lew/applications')}
      />
    );
  }

  const app = lewData.application;
  const tabs: TabDefinition<TabKey>[] = [
    {
      key: 'documents',
      label: 'Documents',
      badge: pendingDocCount > 0
        ? { text: String(pendingDocCount), variant: 'warning' }
        : undefined,
    },
    {
      key: 'kva',
      label: 'kVA',
      badge: kvaLewVerified
        ? { text: 'Confirmed', variant: 'success' }
        : adminApp?.kvaSource === 'USER_INPUT'
          ? { text: 'Review', variant: 'warning' }   // 신청자 신고값 — LEW 확인/확정 필요
          : { text: 'Unknown', variant: 'warning' },
    },
    ...(sldRequired
      ? ([{
          key: 'sld' as TabKey,
          label: 'SLD',
          badge: sldReady
            ? { text: 'Confirmed', variant: 'success' as const }
            : { text: sldRequest?.status ?? 'Missing', variant: 'warning' as const },
        }])
      : []),
    { key: 'loa', label: 'LOA' },
    {
      key: 'ema',
      label: 'EMA',
      // 배지는 EMA 상태별(NOT_SUBMITTED 는 배지 없음 — 스펙 §8.1).
      badge:
        ema.ema && ema.ema.emaSubmissionStatus !== 'NOT_SUBMITTED'
          ? { text: formatEmaStatus(ema.ema.emaSubmissionStatus), variant: getEmaStatusBadge(ema.ema.emaSubmissionStatus) }
          : undefined,
    },
  ];

  const applicantDisplayName =
    adminApp.userFirstName || adminApp.userLastName
      ? `${adminApp.userFirstName ?? ''} ${adminApp.userLastName ?? ''}`.trim()
      : adminApp.userEmail;
  const applicationCode = `APP-${String(adminApp.applicationSeq).padStart(6, '0')}`;

  // 옵션 B sticky 요약 — kVA는 확정값 우선, 없으면 신청값 + (pending confirmation) 표기
  const kvaPending = adminApp.kvaStatus !== 'CONFIRMED';
  const kvaSummary = `${adminApp.selectedKva ?? '?'} kVA${kvaPending ? ' (pending confirmation)' : ''}`;

  return (
    <div className="space-y-6">
      {/* 옵션 B: Sticky 상단 요약 헤더 — 리뷰 중에도 신청 메타가 항상 보이도록. */}
      <div className="sticky top-16 z-30 -mx-4 lg:-mx-6 px-4 lg:px-6 py-2 bg-white border-b border-gray-200">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
          <span className="font-semibold text-gray-800 truncate max-w-[18ch]" title={applicantDisplayName}>
            {applicantDisplayName}
          </span>
          <span className="text-gray-500 truncate max-w-[40ch]" title={app.address}>
            {app.address}
          </span>
          <span className={kvaPending ? 'text-warning-700' : 'text-gray-700'}>
            {kvaSummary}
          </span>
          <StatusBadge status={app.status} />
          <button
            type="button"
            onClick={() => navigate(id ? `/lew/applications/${id}` : '/lew/applications')}
            className="ml-auto text-primary-600 hover:text-primary-700 hover:underline text-sm whitespace-nowrap"
          >
            View full application →
          </button>
        </div>

        {/* 결제 요청 — Phase 1(PENDING_REVIEW/REVISION_REQUESTED)에서만 노출.
            가드 = kVA 확정뿐 (문서·LoA·SLD 는 병렬/결제후 작업이라 제외). 미확정 시 비활성 + kVA 탭 점프. */}
        {inPhase1 && (
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-gray-100 pt-2 text-xs">
            <span className="font-medium text-gray-700">Ready for payment?</span>
            <span id="phase1-progress" className="inline-flex flex-wrap items-center gap-x-3 gap-y-1">
              {/* kVA 항목은 확정 후에도 클릭 가능 — 결제 요청 전에는 LEW 가 언제든 kVA 탭에서
                  값을 수정하고 다시 확정할 수 있다(확정했더라도). */}
              <button
                type="button"
                onClick={() => setActiveTab('kva')}
                className={`inline-flex items-center gap-1 underline-offset-2 hover:underline ${kvaLewVerified ? 'text-success-700' : 'text-warning-700'}`}
              >
                <span aria-hidden>{kvaLewVerified ? '✓' : '•'}</span> kVA
                <span className="sr-only">
                  {kvaLewVerified ? 'confirmed — go to kVA tab to change/re-confirm' : 'not confirmed — go to kVA tab'}
                </span>
                <span aria-hidden> →</span>
              </button>
              {/* 문서·LoA 는 결제 전제가 아님(2026-06-18 — kVA 확정이 충분조건) → 준비바에서 제외.
                  진행 현황은 Documents 탭 배지·LoA 탭·확정 요약 패널에 노출. */}
            </span>
            <Button
              size="sm"
              className="ml-auto"
              disabled={!phase1Ready || requestingPayment}
              aria-disabled={!phase1Ready || requestingPayment}
              aria-describedby={!phase1Ready ? 'phase1-progress' : undefined}
              loading={requestingPayment}
              onClick={() => setShowRequestPaymentConfirm(true)}
            >
              Request payment
            </Button>
          </div>
        )}
      </div>

      {/* Back navigation */}
      <div>
        <button
          type="button"
          onClick={() => navigate(id ? `/lew/applications/${id}` : '/lew/applications')}
          className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
          aria-label="Back to application"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <span>Back to application</span>
        </button>
      </div>

      <PageHeader
        title={`LEW Review — Application #${app.applicationSeq}`}
        subtitle={app.address}
        actions={<StatusBadge status={app.status} />}
      />

      {/* 확정 내역 요약 — 모든 단계에서 항상 노출(결제 요청 후 포함). 결제 전엔 각 항목 수정·재확정 링크. */}
      <LewConfirmationSummary
        application={adminApp}
        loaStatus={loaStatus}
        documentRequests={documentRequests}
        sldRequired={sldRequired}
        sldRequest={sldRequest}
        editable={inPhase1}
        onGoToTab={setActiveTab}
        onDownloadFinalLoa={handleLoaDownload}
      />

      {/* Review comment (ADMIN이 남긴 코멘트, view-only) */}
      {adminApp.reviewComment && (
        <div className="rounded-lg border border-warning-200 bg-warning-50 p-4">
          <p className="text-sm font-semibold text-warning-800">Revision comment from admin</p>
          <p className="text-sm text-warning-700 mt-1 whitespace-pre-wrap">
            {adminApp.reviewComment}
          </p>
        </div>
      )}

      {/* Tabs */}
      <Card padding="none">
        <div className="px-2">
          <Tabs
            tabs={tabs}
            activeKey={activeTab ?? 'documents'}
            onChange={(key) => setActiveTab(key as TabKey)}
          />
        </div>
        <div className="p-6">
          <TabPanel active={activeTab === 'documents'}>
            <LewDocumentReviewSection
              applicationSeq={applicationId}
              canRequest={canRequestDocuments}
              applicantDisplayName={applicantDisplayName}
              applicationCode={applicationCode}
              onRequestsChanged={loadData}
            />
          </TabPanel>

          <TabPanel active={activeTab === 'kva'}>
            <KvaSection application={adminApp} onUpdated={loadData} />
          </TabPanel>

          {sldRequired && (
            <TabPanel active={activeTab === 'sld'}>
              {sldRequest ? (
                <AdminSldSection
                  applicationSeq={applicationId}
                  sldRequest={sldRequest}
                  sldLewNote={sldLewNote}
                  onSldLewNoteChange={setSldLewNote}
                  onSldUpload={handleSldUpload}
                  onSldConfirmClick={() => setShowSldConfirm(true)}
                  onSldUnconfirmClick={handleSldUnconfirm}
                  onSldUpdated={loadData}
                  actionLoading={actionLoading}
                  existingSldFiles={files.filter((f) => f.fileType === 'DRAWING_SLD')}
                  onFileDelete={handleFileDelete}
                  readOnly={isTerminal}
                />
              ) : (
                <InfoBox variant="info">
                  SLD request record is not yet available. The applicant may not have requested it,
                  or the backend record is missing.
                </InfoBox>
              )}
            </TabPanel>
          )}

          <TabPanel active={activeTab === 'loa'}>
            <LewLoaExchangeSection
              applicationType={adminApp.applicationType}
              loaStatus={loaStatus}
              onSendForm={handleSendLoaForm}
              onUploadFinal={handleUploadFinalLoa}
              onDownloadFile={handleLoaDownload}
              sendingForm={loaGenerating}
              uploadingFinal={loaUploading}
            />
          </TabPanel>

          <TabPanel active={activeTab === 'ema'}>
            {/* PAID → IN_PROGRESS 진입: EMA 작업은 IN_PROGRESS 에서만 가능. LEW 가 최종 LoA 업로드 후 직접 시작. */}
            {adminApp.status === 'PAID' && (
              <Card className="mb-4">
                <h2 className="text-lg font-semibold text-gray-800 mb-2">Start processing</h2>
                <p className="text-sm text-gray-600 mb-3">
                  Payment is confirmed. Start processing to move the application to <strong>In&nbsp;Progress</strong>{' '}
                  and unlock EMA ELISE submission below.
                </p>
                {loaStatus?.loaStage === 'FINAL_UPLOADED' ? (
                  <Button
                    onClick={handleStartProcessing}
                    loading={startingProcessing}
                    disabled={startingProcessing}
                  >
                    Start processing
                  </Button>
                ) : (
                  <InfoBox>
                    Upload the final LoA in the{' '}
                    <button
                      type="button"
                      className="font-medium text-primary underline underline-offset-2"
                      onClick={() => setActiveTab('loa')}
                    >
                      LOA tab
                    </button>{' '}
                    before you can start processing.
                  </InfoBox>
                )}
              </Card>
            )}
            <AdminEmaSection
              ema={ema.ema}
              appStatus={adminApp.status}
              isAdmin={currentUser?.role === 'ADMIN' || currentUser?.role === 'SYSTEM_ADMIN'}
              busy={ema.busy}
              onSubmit={ema.submit}
              onQuery={ema.query}
              onResubmit={ema.resubmit}
              onApprove={ema.approve}
              onReject={ema.reject}
              onWithdraw={ema.withdraw}
              onRevert={ema.revert}
              onUploadFile={ema.uploadFile}
              onCompleteClick={() => setShowCompleteModal(true)}
            />
          </TabPanel>
        </div>
      </Card>

      {/* SLD confirm dialog */}
      <ConfirmDialog
        isOpen={showSldConfirm}
        title="Confirm SLD?"
        message="Once confirmed, the SLD will be locked. You can reopen it later if needed."
        confirmLabel="Confirm SLD"
        onConfirm={handleSldConfirm}
        onClose={() => setShowSldConfirm(false)}
      />

      {/* 결제 요청 confirm dialog — 비가역 액션(신청자에게 결제 알림 발송) */}
      <ConfirmDialog
        isOpen={showRequestPaymentConfirm}
        title="Request payment?"
        message="The applicant will be notified by email to pay the licence fee. This moves the application to the payment stage. SLD and LOA are completed after payment."
        confirmLabel="Request payment"
        onConfirm={() => {
          setShowRequestPaymentConfirm(false);
          void runRequestPayment();
        }}
        onClose={() => setShowRequestPaymentConfirm(false)}
      />

      {/* Complete & Issue Licence — EMA 탭의 CTA에서 오픈. 게이트는 백엔드 강제. */}
      <CompleteModal
        isOpen={showCompleteModal}
        onClose={() => setShowCompleteModal(false)}
        onConfirm={handleComplete}
        completeForm={completeForm}
        setCompleteForm={setCompleteForm}
        loading={completing}
      />

      {/* ───────────────────────────────────────────────────────────────────
          옵션 B 사이드바 형태 ② — 데스크톱 (>=1024px) 콜랩서블 사이드바
         ────────────────────────────────────────────────────────────────── */}
      <div className="hidden lg:block">
        {!appInfoOpen && (
          <button
            type="button"
            onClick={() => setAppInfoOpen(true)}
            className="fixed right-0 top-1/2 -translate-y-1/2 z-40 bg-white border border-r-0 border-gray-300 rounded-l-lg shadow hover:bg-gray-50 px-2 py-4 flex flex-col items-center gap-2"
            aria-label="Show application info"
            aria-expanded="false"
          >
            <svg className="w-4 h-4 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span
              className="text-xs font-medium text-gray-700 tracking-wide"
              style={{ writingMode: 'vertical-rl', transform: 'rotate(180deg)' }}
            >
              Application info
            </span>
          </button>
        )}
        {appInfoOpen && (
          <aside
            className="fixed right-0 top-16 bottom-0 z-40 w-[360px] bg-white border-l border-gray-200 shadow-xl flex flex-col"
            aria-label="Application info"
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
              <h3 className="text-sm font-semibold text-gray-800">Application info</h3>
              <button
                type="button"
                onClick={() => setAppInfoOpen(false)}
                className="p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100"
                aria-label="Close application info"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 space-y-4">
              <AdminApplicationInfo
                application={adminApp}
                onNavigateToOriginal={(seq) => navigate(`/lew/applications/${seq}`)}
              />
            </div>
          </aside>
        )}
      </div>

      {/* ───────────────────────────────────────────────────────────────────
          옵션 B 모바일 ⓐ — <1024px 우하단 FAB + 풀스크린 드로어
         ────────────────────────────────────────────────────────────────── */}
      <div className="lg:hidden">
        {!appInfoOpen && (
          <button
            type="button"
            onClick={() => setAppInfoOpen(true)}
            className="fixed bottom-6 right-6 z-40 w-14 h-14 rounded-full bg-primary text-white shadow-lg hover:opacity-90 flex items-center justify-center text-lg font-bold"
            aria-label="Show application info"
          >
            i
          </button>
        )}
        {appInfoOpen && (
          <div className="fixed inset-0 z-50 flex flex-col">
            {/* dim backdrop */}
            <button
              type="button"
              className="absolute inset-0 bg-black/50"
              onClick={() => setAppInfoOpen(false)}
              aria-label="Close application info"
            />
            <div className="relative ml-auto w-full max-w-md h-full bg-white shadow-xl flex flex-col animate-in">
              <div className="flex items-center justify-between px-4 py-3 border-b border-gray-200">
                <h3 className="text-base font-semibold text-gray-800">Application info</h3>
                <button
                  type="button"
                  onClick={() => setAppInfoOpen(false)}
                  className="p-1 rounded-md text-gray-400 hover:text-gray-600 hover:bg-gray-100"
                  aria-label="Close"
                >
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </button>
              </div>
              <div className="flex-1 overflow-y-auto p-4 space-y-4">
                <AdminApplicationInfo
                  application={adminApp}
                  onNavigateToOriginal={(seq) => navigate(`/lew/applications/${seq}`)}
                />
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function ErrorPanel({
  code,
  message,
  onBack,
}: {
  code: string;
  message: string;
  onBack: () => void;
}) {
  const headline =
    code === 'APPLICATION_NOT_ASSIGNED'
      ? "You aren't assigned to this application"
      : code === 'APPLICATION_NOT_FOUND'
        ? 'Application not found'
        : 'Unable to load application';

  return (
    <div className="max-w-xl mx-auto py-12">
      <Card>
        <div className="space-y-4 text-center">
          <div className="mx-auto w-12 h-12 rounded-full bg-error-50 flex items-center justify-center text-error-600">
            <svg className="w-6 h-6" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M12 9v2m0 4h.01M4.93 4.93l14.14 14.14M19.07 4.93L4.93 19.07" />
            </svg>
          </div>
          <h2 className="text-lg font-semibold text-gray-800">{headline}</h2>
          <p className="text-sm text-gray-500">{message}</p>
          <div>
            <Button onClick={onBack}>Back to assigned applications</Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
