import { useCallback, useEffect, useMemo, useState } from 'react';
import type { AxiosError } from 'axios';
import { useNavigate, useParams } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { InfoBox } from '../../components/ui/InfoBox';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import { Tabs, TabPanel, type TabDefinition } from '../../components/ui/Tabs';
import { KvaSection } from '../../components/admin/KvaSection';
import { AdminSldSection } from '../admin/sections/AdminSldSection';
import { LewDocumentReviewSection } from '../../components/document/LewDocumentReviewSection';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import lewReviewApi from '../../api/lewReviewApi';
import adminApi from '../../api/adminApi';
import fileApi from '../../api/fileApi';
import loaApi from '../../api/loaApi';
import documentApi from '../../api/documentApi';
import { useToastStore } from '../../stores/toastStore';
import { useAuthStore } from '../../stores/authStore';
import type {
  CertificateOfFitnessRequest,
  CertificateOfFitnessResponse,
  InspectionInterval,
  LewApplicationResponse,
  SupplyVoltage,
} from '../../types/cof';
import type { ConsumerType, RetailerCode } from '../../constants/cof';
import type {
  AdminApplication,
  DocumentRequest,
  FileInfo,
  LoaStatus,
  SldRequest,
} from '../../types';
import { CofStepApplicationSummary } from './sections/CofStepApplicationSummary';
import { CofStepInputs } from './sections/CofStepInputs';
import { CofStepReviewFinalize } from './sections/CofStepReviewFinalize';

/**
 * LEW 통합 리뷰 페이지 (Phase 6).
 *
 * URL: `/lew/applications/:id/review`
 * 권한: LEW 역할만 (ProtectedRoute). 배정 여부는 백엔드 `@appSec.isAssignedLew`가 최종 판정.
 *
 * <h3>5개 탭</h3>
 * <ol>
 *   <li>Documents — LEW가 신청자에게 서류 요청·검토</li>
 *   <li>kVA — LEW 확정 (Application.selectedKva SSOT)</li>
 *   <li>SLD — sldOption=REQUEST_LEW 일 때만 노출</li>
 *   <li>LOA — view-only (LEW는 수정 불가)</li>
 *   <li>Certificate of Fitness — 기존 3-step 흐름(Summary/Inputs/Finalize)을 탭 내부에 유지</li>
 * </ol>
 *
 * <h3>Finalize 가드</h3>
 * 다음 3조건을 모두 만족해야 CoF finalize 가능:
 * <ul>
 *   <li>{@code kvaStatus === 'CONFIRMED'}</li>
 *   <li>미해결 DocumentRequest 0건 (REQUESTED/UPLOADED 없음)</li>
 *   <li>{@code sldOption === 'REQUEST_LEW'} 인 경우 SLD {@code status === 'CONFIRMED'}</li>
 * </ul>
 * 가드 미충족 시 Finalize 버튼 disabled + 해당 탭으로 이동 안내.
 */

const COF_STEPS = [
  { label: 'Summary', description: 'Applicant inputs' },
  { label: 'CoF Fields', description: 'Enter CoF details' },
  { label: 'Finalize', description: 'Review & submit' },
];

type TabKey = 'documents' | 'kva' | 'sld' | 'loa' | 'cof';

type ApiErrorShape = AxiosError<{ code?: string; message?: string }> & {
  code?: string;
  message?: string;
};

export default function LewReviewFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToastStore();
  const { user: currentUser } = useAuthStore();

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<{ code: string; message: string } | null>(null);

  // LEW 전용 응답 (CoF draft + hint + MSSL 평문)
  const [lewData, setLewData] = useState<LewApplicationResponse | null>(null);
  // /api/admin/applications/{id} — KvaSection/LOA/SLD 모두 이 형상 요구
  const [adminApp, setAdminApp] = useState<AdminApplication | null>(null);
  const [loaStatus, setLoaStatus] = useState<LoaStatus | null>(null);
  const [sldRequest, setSldRequest] = useState<SldRequest | null>(null);
  const [files, setFiles] = useState<FileInfo[]>([]);
  const [documentRequests, setDocumentRequests] = useState<DocumentRequest[]>([]);
  const [sldLewNote, setSldLewNote] = useState('');
  const [showSldConfirm, setShowSldConfirm] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);

  const [activeTab, setActiveTab] = useState<TabKey>('cof');
  const [currentStep, setCurrentStep] = useState<0 | 1 | 2>(0);
  const [draft, setDraft] = useState<CertificateOfFitnessRequest>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [finalizing, setFinalizing] = useState(false);

  const applicationId = id ? Number(id) : NaN;
  const idValid = Number.isFinite(applicationId) && applicationId > 0;

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
      setDraft(buildInitialDraft(lewRes, adminRes));

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

  // ── Derived / Guards ─────────────────────────────────
  const pendingDocCount = useMemo(
    () => documentRequests.filter((d) => d.status === 'REQUESTED' || d.status === 'UPLOADED').length,
    [documentRequests],
  );
  const kvaConfirmed = adminApp?.kvaStatus === 'CONFIRMED';
  const sldRequired = adminApp?.sldOption === 'REQUEST_LEW';
  const sldReady = !sldRequired || sldRequest?.status === 'CONFIRMED';
  const guardsSatisfied = kvaConfirmed && pendingDocCount === 0 && sldReady;

  const cofFinalized = lewData?.cof?.finalized === true;

  // Phase 3 권한: LEW는 assigned_lew_seq 일치 시만 서류 요청 가능
  const canRequestDocuments =
    currentUser?.role === 'LEW' &&
    !!adminApp?.assignedLewSeq &&
    adminApp.assignedLewSeq === currentUser?.userSeq;

  // ── Draft 편집 ───────────────────────────────────────
  const handleDraftChange = useCallback((patch: Partial<CertificateOfFitnessRequest>) => {
    setDraft((prev) => ({ ...prev, ...patch }));
    setErrors((prev) => {
      const next = { ...prev };
      Object.keys(patch).forEach((k) => {
        delete next[k];
      });
      return next;
    });
  }, []);

  // ── Save Draft ───────────────────────────────────────
  const handleSaveDraft = useCallback(async () => {
    if (!idValid) return;
    setSaving(true);
    try {
      const saved: CertificateOfFitnessResponse = await lewReviewApi.saveDraftCof(
        applicationId,
        draft,
      );
      setDraft(responseToRequest(saved));
      setLewData((prev) => (prev ? { ...prev, cof: saved } : prev));
      toast.success('Draft saved');
    } catch (err) {
      const { code, message } = extractError(err);
      if (code === 'COF_VERSION_CONFLICT') {
        toast.warning('This CoF was updated elsewhere. Reloading latest version…');
        await loadData();
      } else if (code === 'COF_ALREADY_FINALIZED') {
        toast.warning('This CoF has already been finalized.');
        await loadData();
      } else {
        toast.error(message || 'Failed to save draft');
      }
    } finally {
      setSaving(false);
    }
  }, [applicationId, draft, idValid, loadData, toast]);

  const handleNextFromInputs = useCallback(() => {
    const errs = validateDraftForFinalize(draft);
    if (Object.keys(errs).length > 0) {
      setErrors(errs);
      toast.error('Please complete all required fields before continuing.');
      return;
    }
    setErrors({});
    setCurrentStep(2);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, [draft, toast]);

  // ── Finalize (with Phase 6 guards + error mapping) ───
  const handleFinalize = useCallback(async () => {
    if (!idValid) return;
    const errs = validateDraftForFinalize(draft);
    if (Object.keys(errs).length > 0) {
      setErrors(errs);
      setCurrentStep(1);
      toast.error('Missing required fields — returning to CoF fields.');
      return;
    }
    if (!guardsSatisfied) {
      if (!kvaConfirmed) {
        toast.error('kVA must be confirmed first.');
        setActiveTab('kva');
      } else if (pendingDocCount > 0) {
        toast.error(`Resolve ${pendingDocCount} pending document request(s) first.`);
        setActiveTab('documents');
      } else if (!sldReady) {
        toast.error('SLD must be uploaded and confirmed first.');
        setActiveTab('sld');
      }
      return;
    }
    setFinalizing(true);
    try {
      await lewReviewApi.saveDraftCof(applicationId, draft);
      await lewReviewApi.finalizeCof(applicationId);
      toast.success('Certificate of Fitness finalized. Application moved to payment stage.');
      navigate('/lew/applications');
    } catch (err) {
      const { code, message } = extractError(err);
      if (code === 'COF_ALREADY_FINALIZED') {
        toast.warning('This CoF has already been finalized.');
        await loadData();
      } else if (code === 'COF_VERSION_CONFLICT') {
        toast.warning('Someone else edited this CoF. Reloading…');
        await loadData();
      } else if (code === 'APPLICATION_NOT_ASSIGNED') {
        toast.error('You are not assigned to this application.');
        navigate('/lew/applications');
      } else if (code === 'KVA_NOT_CONFIRMED') {
        toast.error('kVA must be confirmed before finalizing CoF.');
        setActiveTab('kva');
        await loadData();
      } else if (code === 'DOCUMENT_REQUESTS_PENDING') {
        toast.error('Pending document requests block finalization.');
        setActiveTab('documents');
        await loadData();
      } else if (code === 'SLD_NOT_CONFIRMED') {
        toast.error('SLD must be uploaded and confirmed before finalizing CoF.');
        setActiveTab('sld');
        await loadData();
      } else {
        toast.error(message || 'Failed to finalize CoF');
      }
    } finally {
      setFinalizing(false);
    }
  }, [
    applicationId, draft, guardsSatisfied, idValid, kvaConfirmed, loadData,
    navigate, pendingDocCount, sldReady, toast,
  ]);

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
      badge: kvaConfirmed
        ? { text: 'Confirmed', variant: 'success' }
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
      key: 'cof',
      label: 'Certificate of Fitness',
      badge: cofFinalized
        ? { text: 'Finalized', variant: 'success' }
        : guardsSatisfied
          ? { text: 'Ready', variant: 'info' }
          : { text: 'Blocked', variant: 'gray' },
    },
  ];

  const applicantDisplayName =
    adminApp.userFirstName || adminApp.userLastName
      ? `${adminApp.userFirstName ?? ''} ${adminApp.userLastName ?? ''}`.trim()
      : adminApp.userEmail;
  const applicationCode = `APP-${String(adminApp.applicationSeq).padStart(6, '0')}`;

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => navigate('/lew/applications')}
            className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
            aria-label="Back to assigned applications"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            <span>Back</span>
          </button>
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-gray-800">
              LEW Review — Application #{app.applicationSeq}
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">{app.address}</p>
          </div>
        </div>
        <StatusBadge status={app.status} />
      </div>

      {/* Review comment (ADMIN이 남긴 코멘트, view-only) */}
      {adminApp.reviewComment && (
        <div className="rounded-lg border border-warning-200 bg-warning-50 p-4">
          <p className="text-sm font-semibold text-warning-800">Revision comment from admin</p>
          <p className="text-sm text-warning-700 mt-1 whitespace-pre-wrap">
            {adminApp.reviewComment}
          </p>
        </div>
      )}

      {/* Finalized banner */}
      {cofFinalized && (
        <InfoBox variant="info">
          This Certificate of Fitness has been finalized on{' '}
          <strong>{lewData.cof?.certifiedAt ?? 'unknown date'}</strong>. The application has moved to
          payment stage.
        </InfoBox>
      )}

      {/* Tabs */}
      <Card padding="none">
        <div className="px-2">
          <Tabs tabs={tabs} activeKey={activeTab} onChange={setActiveTab} />
        </div>
        <div className="p-6">
          <TabPanel active={activeTab === 'documents'}>
            <LewDocumentReviewSection
              applicationSeq={applicationId}
              canRequest={canRequestDocuments}
              applicantDisplayName={applicantDisplayName}
              applicationCode={applicationCode}
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
            <LoaReadOnlyView loaStatus={loaStatus} application={adminApp} />
          </TabPanel>

          <TabPanel active={activeTab === 'cof'}>
            <div className="space-y-6">
              <Card>
                <StepTracker steps={COF_STEPS} currentStep={currentStep} />
              </Card>
              <Card>
                {currentStep === 0 && (
                  <CofStepApplicationSummary data={lewData} onNext={() => setCurrentStep(1)} />
                )}
                {currentStep === 1 && (
                  <CofStepInputs
                    data={lewData}
                    draft={draft}
                    onDraftChange={handleDraftChange}
                    onPrevious={() => setCurrentStep(0)}
                    onSaveDraft={handleSaveDraft}
                    onNext={handleNextFromInputs}
                    saving={saving}
                    errors={errors}
                    readOnly={cofFinalized}
                  />
                )}
                {currentStep === 2 && (
                  <CofStepReviewFinalize
                    draft={draft}
                    confirmed={confirmed}
                    onConfirmedChange={setConfirmed}
                    onPrevious={() => setCurrentStep(1)}
                    onSaveDraft={handleSaveDraft}
                    onFinalize={handleFinalize}
                    saving={saving}
                    finalizing={finalizing}
                    readOnly={cofFinalized}
                    guards={{
                      kvaConfirmed,
                      pendingDocCount,
                      sldRequired,
                      sldReady,
                    }}
                    onJumpToTab={(key) => setActiveTab(key as TabKey)}
                  />
                )}
              </Card>
            </div>
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
    </div>
  );
}

/**
 * LOA view-only 패널. LEW는 LOA 생성/업로드 권한이 없다 (백엔드 URL 매처 차단).
 */
function LoaReadOnlyView({
  loaStatus,
  application,
}: {
  loaStatus: LoaStatus | null;
  application: AdminApplication;
}) {
  const signed = !!application.loaSignedAt;
  const isRenewal = application.applicationType === 'RENEWAL';
  return (
    <div className="space-y-3">
      <h2 className="text-lg font-semibold text-gray-800">Letter of Authority</h2>
      <p className="text-sm text-gray-500">
        LOA is managed by ADMIN. LEW can view its status here as part of the review workflow.
      </p>
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-4 space-y-2 text-sm">
        <div className="flex justify-between">
          <span className="text-gray-600">Type</span>
          <span className="font-medium text-gray-800">{isRenewal ? 'Renewal (uploaded)' : 'New (generated)'}</span>
        </div>
        <div className="flex justify-between">
          <span className="text-gray-600">Signature status</span>
          <span className="font-medium text-gray-800">
            {signed ? 'Signed' : 'Not signed yet'}
          </span>
        </div>
        {application.loaSignedAt && (
          <div className="flex justify-between">
            <span className="text-gray-600">Signed at</span>
            <span className="font-medium text-gray-800">
              {new Date(application.loaSignedAt).toLocaleString()}
            </span>
          </div>
        )}
        {loaStatus && (
          <div className="flex justify-between">
            <span className="text-gray-600">LOA file</span>
            <span className="font-medium text-gray-800">
              {loaStatus.loaFileSeq ? `#${loaStatus.loaFileSeq}` : 'Not generated'}
            </span>
          </div>
        )}
      </div>
      {!signed && (
        <div className="rounded-lg border border-warning-200 bg-warning-50 p-3 text-xs text-warning-700">
          LOA signature is pending. ADMIN or the applicant must complete the signature before this
          application can progress.
        </div>
      )}
    </div>
  );
}

/** CoF Draft 존재 시 우선, 없으면 hint + today prefill. */
function buildInitialDraft(
  data: LewApplicationResponse,
  adminApp: AdminApplication,
): CertificateOfFitnessRequest {
  const today = new Date().toISOString().slice(0, 10);
  if (data.cof) {
    const base = responseToRequest(data.cof);
    // Phase 6: kVA는 Application.selectedKva SSOT. Draft 응답 값보다 현재 Application 값 우선.
    return { ...base, approvedLoadKva: adminApp.selectedKva };
  }
  return {
    msslAccountNo: data.msslHintPlain || undefined,
    consumerType: data.consumerTypeHint,
    retailerCode: data.retailerHint,
    supplyVoltageV:
      data.supplyVoltageHint != null ? (data.supplyVoltageHint as SupplyVoltage) : undefined,
    approvedLoadKva: adminApp.kvaStatus === 'UNKNOWN' ? undefined : adminApp.selectedKva,
    hasGenerator: data.hasGeneratorHint ?? false,
    generatorCapacityKva: data.generatorCapacityHint,
    inspectionIntervalMonths: undefined,
    lewAppointmentDate: today,
    lewConsentDate: undefined,
  };
}

function responseToRequest(cof: CertificateOfFitnessResponse): CertificateOfFitnessRequest {
  return {
    msslAccountNo: cof.msslAccountNo,
    consumerType: cof.consumerType as ConsumerType | undefined,
    retailerCode: cof.retailerCode as RetailerCode | undefined,
    supplyVoltageV: cof.supplyVoltageV as SupplyVoltage | undefined,
    approvedLoadKva: cof.approvedLoadKva,
    hasGenerator: cof.hasGenerator,
    generatorCapacityKva: cof.generatorCapacityKva,
    inspectionIntervalMonths:
      cof.inspectionIntervalMonths as InspectionInterval | undefined,
    lewAppointmentDate: cof.lewAppointmentDate,
    lewConsentDate: cof.lewConsentDate,
  };
}

function validateDraftForFinalize(draft: CertificateOfFitnessRequest): Record<string, string> {
  const errs: Record<string, string> = {};
  const msslRegex = /^\d{3}-\d{2}-\d{4}-\d$/;
  if (!draft.msslAccountNo || !msslRegex.test(draft.msslAccountNo)) {
    errs.msslAccountNo = 'Enter all 10 MSSL digits (format ###-##-####-#).';
  }
  if (!draft.consumerType) {
    errs.consumerType = 'Pick a consumer type.';
  }
  if (draft.consumerType === 'CONTESTABLE' && !draft.retailerCode) {
    errs.retailerCode = 'Retailer is required for contestable supply.';
  }
  if (!draft.supplyVoltageV) {
    errs.supplyVoltageV = 'Pick a supply voltage.';
  }
  if (!draft.approvedLoadKva || draft.approvedLoadKva <= 0) {
    errs.approvedLoadKva = 'Approved load (kVA) must be confirmed.';
  }
  if (draft.hasGenerator && (!draft.generatorCapacityKva || draft.generatorCapacityKva <= 0)) {
    errs.generatorCapacityKva = 'Generator capacity is required when a generator is present.';
  }
  if (!draft.inspectionIntervalMonths) {
    errs.inspectionIntervalMonths = 'Pick an inspection interval.';
  }
  if (!draft.lewAppointmentDate) {
    errs.lewAppointmentDate = 'LEW appointment date is required.';
  }
  return errs;
}

function extractError(err: unknown): { code: string; message: string } {
  const e = err as ApiErrorShape;
  const code =
    (e as unknown as { code?: string }).code ||
    e.response?.data?.code ||
    'UNKNOWN';
  const message =
    (e as unknown as { message?: string }).message ||
    e.response?.data?.message ||
    'Unknown error';
  return { code, message };
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
