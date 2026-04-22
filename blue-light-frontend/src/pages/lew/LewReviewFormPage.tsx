import { useCallback, useEffect, useState } from 'react';
import type { AxiosError } from 'axios';
import { useNavigate, useParams } from 'react-router-dom';
import { Button } from '../../components/ui/Button';
import { Card } from '../../components/ui/Card';
import { InfoBox } from '../../components/ui/InfoBox';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { StatusBadge } from '../../components/domain/StatusBadge';
import { StepTracker } from '../../components/domain/StepTracker';
import lewReviewApi from '../../api/lewReviewApi';
import { useToastStore } from '../../stores/toastStore';
import type {
  CertificateOfFitnessRequest,
  CertificateOfFitnessResponse,
  InspectionInterval,
  LewApplicationResponse,
  SupplyVoltage,
} from '../../types/cof';
import type { ConsumerType, RetailerCode } from '../../constants/cof';
import { CofStepApplicationSummary } from './sections/CofStepApplicationSummary';
import { CofStepInputs } from './sections/CofStepInputs';
import { CofStepReviewFinalize } from './sections/CofStepReviewFinalize';

/**
 * LEW Review Form — 3-step CoF 입력/확정 페이지.
 *
 * URL: `/lew/applications/:id/review`
 * 권한: LEW 역할만 (ProtectedRoute). 배정 여부는 백엔드 `@appSec.isAssignedLew`가 최종 판정.
 *
 * 상태 전이:
 *   Step 1 (요약) → Step 2 (CoF 입력, Save Draft 허용) → Step 3 (Review, Finalize)
 *
 * 낙관적 락: PUT `/cof` 응답의 `version`을 사용하지만, 현재 백엔드는 IF-Match 헤더가 아닌
 * `@Version` 컬럼으로 충돌 감지. 프론트는 draft 내부에 버전을 보관하지 않고 응답에 따라
 * 갱신된 값을 다시 반영한다.
 */

const STEPS = [
  { label: 'Summary', description: 'Applicant inputs' },
  { label: 'CoF Fields', description: 'Enter CoF details' },
  { label: 'Finalize', description: 'Review & submit' },
];

type ApiErrorShape = AxiosError<{ code?: string; message?: string }> & {
  code?: string;
  message?: string;
};

export default function LewReviewFormPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const toast = useToastStore();

  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<{ code: string; message: string } | null>(null);
  const [data, setData] = useState<LewApplicationResponse | null>(null);

  const [currentStep, setCurrentStep] = useState<0 | 1 | 2>(0);
  const [draft, setDraft] = useState<CertificateOfFitnessRequest>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmed, setConfirmed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [finalizing, setFinalizing] = useState(false);

  const applicationId = id ? Number(id) : NaN;
  const idValid = Number.isFinite(applicationId) && applicationId > 0;

  // ── Data load + prefill ──────────────────────────────
  const loadData = useCallback(async () => {
    if (!idValid) return;
    setLoading(true);
    setLoadError(null);
    try {
      const res = await lewReviewApi.getAssignedApplication(applicationId);
      setData(res);
      setDraft(buildInitialDraft(res));
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
  }, [applicationId, idValid]);

  useEffect(() => {
    if (!idValid) {
      setLoadError({ code: 'INVALID_ID', message: 'Invalid application id' });
      setLoading(false);
      return;
    }
    void loadData();
  }, [idValid, loadData]);

  // Merge partial draft updates immutably.
  const handleDraftChange = useCallback(
    (patch: Partial<CertificateOfFitnessRequest>) => {
      setDraft((prev) => ({ ...prev, ...patch }));
      // 관련 필드 에러 제거
      setErrors((prev) => {
        const next = { ...prev };
        Object.keys(patch).forEach((k) => {
          delete next[k];
        });
        return next;
      });
    },
    [],
  );

  // Finalized flag — 서버 응답 기준
  const cofFinalized = data?.cof?.finalized === true;

  // ── Save Draft ────────────────────────────────────────
  const handleSaveDraft = useCallback(async () => {
    if (!idValid) return;
    setSaving(true);
    try {
      const saved: CertificateOfFitnessResponse = await lewReviewApi.saveDraftCof(
        applicationId,
        draft,
      );
      // 응답 값으로 draft 갱신 (서버가 정규화한 값·version 반영)
      setDraft(responseToRequest(saved));
      setData((prev) => (prev ? { ...prev, cof: saved } : prev));
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

  // ── Next (Step 2 → 3) with client-side required validation ─
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

  // ── Finalize ──────────────────────────────────────────
  const handleFinalize = useCallback(async () => {
    if (!idValid) return;
    // 최종 검증 한 번 더
    const errs = validateDraftForFinalize(draft);
    if (Object.keys(errs).length > 0) {
      setErrors(errs);
      setCurrentStep(1);
      toast.error('Missing required fields — returning to CoF fields.');
      return;
    }
    setFinalizing(true);
    try {
      // 최신 Draft를 서버에 먼저 저장해 version 일관성 확보
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
      } else {
        toast.error(message || 'Failed to finalize CoF');
      }
    } finally {
      setFinalizing(false);
    }
  }, [applicationId, draft, idValid, loadData, navigate, toast]);

  // ── Render ────────────────────────────────────────────
  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <LoadingSpinner size="lg" label="Loading application…" />
      </div>
    );
  }

  if (loadError || !data) {
    return (
      <ErrorPanel
        code={loadError?.code ?? 'UNKNOWN'}
        message={loadError?.message ?? 'Failed to load application'}
        onBack={() => navigate('/lew/applications')}
      />
    );
  }

  const app = data.application;

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
              Certificate of Fitness Review
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Application #{app.applicationSeq} — {app.address}
            </p>
          </div>
        </div>
        <StatusBadge status={app.status} />
      </div>

      {/* Finalized banner */}
      {cofFinalized && (
        <InfoBox variant="info">
          This Certificate of Fitness has been finalized on{' '}
          <strong>{data.cof?.certifiedAt ?? 'unknown date'}</strong>. The application has moved to
          payment stage.
        </InfoBox>
      )}

      {/* Stepper */}
      <Card>
        <StepTracker steps={STEPS} currentStep={currentStep} />
      </Card>

      {/* Step content */}
      <Card>
        {currentStep === 0 && (
          <CofStepApplicationSummary data={data} onNext={() => setCurrentStep(1)} />
        )}
        {currentStep === 1 && (
          <CofStepInputs
            data={data}
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
          />
        )}
      </Card>
    </div>
  );
}

/** CoF Draft 존재 시 우선, 없으면 hint + today prefill. */
function buildInitialDraft(data: LewApplicationResponse): CertificateOfFitnessRequest {
  const today = new Date().toISOString().slice(0, 10);
  const app = data.application;
  if (data.cof) {
    return responseToRequest(data.cof);
  }
  // hint 기반 prefill — MSSL 평문이 있으면 그대로 채워주고, 없으면 LEW가 현장에서 입력
  return {
    msslAccountNo: data.msslHintPlain || undefined,
    consumerType: data.consumerTypeHint,
    retailerCode: data.retailerHint,
    supplyVoltageV:
      data.supplyVoltageHint != null ? (data.supplyVoltageHint as SupplyVoltage) : undefined,
    approvedLoadKva:
      app.kvaStatus === 'UNKNOWN' ? undefined : (app.selectedKva as number | undefined),
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

/** Finalize 직전에 요구되는 필수 필드 검증 — Draft Save는 이 검증을 적용하지 않는다. */
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
    errs.approvedLoadKva = 'Enter the approved load (kVA).';
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
  // axiosClient 인터셉터가 ApiError.code/message로 정규화
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
