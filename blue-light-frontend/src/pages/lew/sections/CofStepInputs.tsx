import { useMemo } from 'react';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { InfoBox } from '../../../components/ui/InfoBox';
import { Select } from '../../../components/ui/Select';
import { MsslAccountInput } from '../../../components/domain/MsslAccountInput';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
  type RetailerCode,
} from '../../../constants/cof';
import type {
  CertificateOfFitnessRequest,
  InspectionInterval,
  LewApplicationResponse,
  SupplyVoltage,
} from '../../../types/cof';

/**
 * Step 2 — Certificate of Fitness Inputs.
 *
 * LEW가 CoF 10 필드를 기입/교정한다. Draft Save는 부분 입력 허용, Next 진행 시만
 * 필수 검증한다(스펙 §6 Step 2·§9-9).
 *
 * "신청자 기입값" 배지 규칙:
 * - prefill된 값(`applicationMeta.*HintProvided=true` & LEW가 편집하지 않음): "From applicant" 배지 (info).
 * - LEW가 편집함: "From applicant (edited)" 배지 (warning).
 * - 원본 hint가 없거나 LEW가 직접 입력한 경우: 배지 없음.
 *
 * MSSL hint 평문은 백엔드 LewApplicationResponse에 포함되지 않는다(last4만 노출).
 * 따라서 MSSL은 기존 CoF Draft(`cof.msslAccountNo` 평문)가 있는 경우에만 prefill되며,
 * hint만 있고 CoF Draft가 없으면 input은 빈 값으로 열리고 "Applicant ended in ••••-•••X"
 * 안내 문구로 마지막 자리만 표시해 LEW가 확인할 수 있도록 한다.
 */
export interface CofStepInputsProps {
  data: LewApplicationResponse;
  draft: CertificateOfFitnessRequest;
  onDraftChange: (patch: Partial<CertificateOfFitnessRequest>) => void;
  onPrevious: () => void;
  onSaveDraft: () => Promise<void> | void;
  onNext: () => void;
  saving: boolean;
  /** Step 2 내부 필드 인라인 에러 (Next 클릭 시 검증 실패 메시지). */
  errors: Record<string, string>;
  /** Finalize된 상태면 모든 필드 disabled. */
  readOnly?: boolean;
}

export function CofStepInputs({
  data,
  draft,
  onDraftChange,
  onPrevious,
  onSaveDraft,
  onNext,
  saving,
  errors,
  readOnly = false,
}: CofStepInputsProps) {
  const app = data.application;
  const applicantKva = app.selectedKva;
  const kvaUnknown = app.kvaStatus === 'UNKNOWN';

  // hint 배지 결정 — prefill된 값과 현재 draft가 일치하면 "From applicant", 다르면 "edited".
  // hint 자체가 없으면 배지 없음.
  const msslLast4 = data.msslHintLast4;
  const msslBadge = badgeForHint({
    provided: data.msslHintProvided === true,
    // MSSL 평문이 hint 응답에 없으므로 "편집됨" 판단은 CoF draft가 존재하는지 여부로만 간접 추정.
    // 보수적으로 draft.msslAccountNo가 비어있지 않으면 LEW가 입력한 것으로 간주해 "edited" 표기.
    edited:
      (draft.msslAccountNo ?? '').trim().length > 0 &&
      !(
        data.cof?.msslAccountNo &&
        draft.msslAccountNo === data.cof.msslAccountNo
      ),
  });

  const consumerBadge = badgeForHint({
    provided: data.consumerTypeHintProvided === true,
    edited:
      data.consumerTypeHintProvided === true &&
      draft.consumerType != null &&
      draft.consumerType !== data.consumerTypeHint,
  });

  const retailerBadge = badgeForHint({
    provided: data.retailerHintProvided === true,
    edited:
      data.retailerHintProvided === true &&
      draft.retailerCode != null &&
      draft.retailerCode !== data.retailerHint,
  });

  const voltageBadge = badgeForHint({
    provided: data.supplyVoltageHintProvided === true,
    edited:
      data.supplyVoltageHintProvided === true &&
      draft.supplyVoltageV != null &&
      draft.supplyVoltageV !== data.supplyVoltageHint,
  });

  const generatorBadge = badgeForHint({
    provided: data.generatorHintProvided === true,
    edited:
      data.generatorHintProvided === true &&
      ((draft.hasGenerator !== data.hasGeneratorHint && draft.hasGenerator != null) ||
        (draft.generatorCapacityKva !== data.generatorCapacityHint &&
          draft.generatorCapacityKva != null)),
  });

  // 45kVA 이상 Contestable 추천 배너
  const contestableHint = useMemo(() => {
    if (kvaUnknown || !applicantKva) return false;
    return applicantKva >= 45 && draft.consumerType !== 'CONTESTABLE';
  }, [applicantKva, kvaUnknown, draft.consumerType]);

  // Retailer는 Consumer Type이 NON_CONTESTABLE이면 SP 고정 + disabled
  const retailerDisabled = draft.consumerType !== 'CONTESTABLE';

  // Phase 6: CoF.approvedLoadKva 는 Application.selectedKva SSOT. LEW 는 CoF 화면에서 편집 불가
  // (kVA 탭에서 Confirm/Override 해야 함). 값이 비어 있으면 kVA 탭으로 이동을 안내한다.

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-gray-800">Certificate of Fitness</h2>
        <p className="text-sm text-gray-500 mt-1">
          Fill in the 10 CoF fields. Draft Save is allowed at any time; Finalize requires all
          required fields.
        </p>
      </div>

      {contestableHint && (
        <InfoBox>
          Applicant-reported capacity is {applicantKva} kVA. Installations at 45 kVA or above are
          usually <strong>Contestable</strong> — please confirm on site.
        </InfoBox>
      )}

      {/* 1. MSSL */}
      <div className="space-y-2">
        <div className="flex items-center gap-2">
          <label
            htmlFor="mssl-part-0"
            className="block text-sm font-medium text-gray-700"
          >
            MSSL Account No <span className="text-error-500">*</span>
          </label>
          {msslBadge}
        </div>
        <MsslAccountInput
          value={draft.msslAccountNo ?? ''}
          onChange={(v) => onDraftChange({ msslAccountNo: v || undefined })}
          disabled={readOnly}
        />
        {data.msslHintProvided && !data.cof?.msslAccountNo && !data.msslHintPlain && msslLast4 && (
          <p className="text-xs text-gray-500">
            Applicant provided a hint ending in <span className="font-mono">•••-••-••••-{msslLast4.slice(-1)}</span>.
            Please re-enter after confirming on site.
          </p>
        )}
        {errors.msslAccountNo && (
          <p className="text-xs text-error-600">{errors.msslAccountNo}</p>
        )}
      </div>

      {/* 2. Consumer Type */}
      <fieldset className="space-y-2">
        <div className="flex items-center gap-2">
          <legend className="text-sm font-medium text-gray-700">
            Consumer Type <span className="text-error-500">*</span>
          </legend>
          {consumerBadge}
        </div>
        <div className="space-y-2">
          {CONSUMER_TYPE_OPTIONS.map((opt) => {
            const checked = draft.consumerType === opt.value;
            return (
              <label
                key={opt.value}
                className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors ${
                  checked
                    ? 'border-primary-500 bg-primary-50'
                    : 'border-gray-200 hover:border-gray-300'
                } ${readOnly ? 'pointer-events-none opacity-60' : ''}`}
              >
                <input
                  type="radio"
                  name="consumerType"
                  value={opt.value}
                  checked={checked}
                  onChange={() => {
                    const next: Partial<CertificateOfFitnessRequest> = {
                      consumerType: opt.value,
                    };
                    if (opt.value === 'NON_CONTESTABLE') {
                      next.retailerCode = 'SP_SERVICES_LIMITED';
                    }
                    onDraftChange(next);
                  }}
                  disabled={readOnly}
                  className="mt-0.5 h-4 w-4 text-primary-600 focus:ring-primary-500"
                />
                <span>
                  <span className="block text-sm font-medium text-gray-800">{opt.label}</span>
                  <span className="mt-0.5 block text-xs text-gray-500">{opt.description}</span>
                </span>
              </label>
            );
          })}
        </div>
        {errors.consumerType && (
          <p className="text-xs text-error-600">{errors.consumerType}</p>
        )}
      </fieldset>

      {/* 3. Retailer */}
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <label className="block text-sm font-medium text-gray-700">
            Retailer {draft.consumerType === 'CONTESTABLE' && (
              <span className="text-error-500">*</span>
            )}
          </label>
          {retailerBadge}
        </div>
        <Select
          value={retailerDisabled ? 'SP_SERVICES_LIMITED' : (draft.retailerCode ?? '')}
          onChange={(e) =>
            onDraftChange({
              retailerCode: (e.target.value || undefined) as RetailerCode | undefined,
            })
          }
          disabled={retailerDisabled || readOnly}
          options={
            retailerDisabled
              ? [{ value: 'SP_SERVICES_LIMITED', label: 'SP Services Limited' }]
              : [
                  { value: '', label: 'Pick a retailer' },
                  ...RETAILER_OPTIONS.map((o) => ({ value: o.value, label: o.label })),
                ]
          }
          hint={
            retailerDisabled
              ? 'Non-contestable supply is delivered by SP Services.'
              : undefined
          }
          error={errors.retailerCode}
        />
      </div>

      {/* 4. Supply Voltage */}
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <label className="block text-sm font-medium text-gray-700">
            Supply Voltage <span className="text-error-500">*</span>
          </label>
          {voltageBadge}
        </div>
        <Select
          value={draft.supplyVoltageV ? String(draft.supplyVoltageV) : ''}
          onChange={(e) => {
            const v = e.target.value;
            onDraftChange({
              supplyVoltageV: (v ? Number(v) : undefined) as SupplyVoltage | undefined,
            });
          }}
          disabled={readOnly}
          options={[
            { value: '', label: 'Pick a voltage' },
            ...SUPPLY_VOLTAGE_OPTIONS.map((o) => ({
              value: String(o.value),
              label: o.label,
            })),
          ]}
          error={errors.supplyVoltageV}
        />
      </div>

      {/* 5. Approved Load kVA — Phase 6: Application.selectedKva SSOT, read-only here */}
      <div className="space-y-1.5">
        <div className="flex items-center gap-2">
          <label className="block text-sm font-medium text-gray-700">
            Approved Load (kVA) <span className="text-error-500">*</span>
          </label>
          {kvaUnknown ? (
            <Badge variant="warning">kVA not confirmed</Badge>
          ) : (
            <Badge variant="success">LEW confirmed</Badge>
          )}
        </div>
        <div className="rounded-lg border border-gray-200 bg-gray-50 p-3 flex items-center justify-between">
          <span className="text-base font-semibold text-gray-800">
            {draft.approvedLoadKva != null ? `${draft.approvedLoadKva} kVA` : '—'}
          </span>
          <span className="text-xs text-gray-500">
            {kvaUnknown
              ? 'Open the kVA tab to confirm capacity on site.'
              : 'Synced from Application. Use the kVA tab to override.'}
          </span>
        </div>
        {errors.approvedLoadKva && (
          <p className="text-xs text-error-600">{errors.approvedLoadKva}</p>
        )}
      </div>

      {/* 6. Generator */}
      <div className="space-y-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-gray-700">Generator on site</span>
          {generatorBadge}
        </div>
        <div className="flex items-center justify-between gap-3">
          <span className="text-xs text-gray-500">
            Turn on if a backup generator is installed.
          </span>
          <button
            type="button"
            role="switch"
            aria-checked={draft.hasGenerator === true}
            onClick={() => {
              if (readOnly) return;
              const next = !(draft.hasGenerator === true);
              const patch: Partial<CertificateOfFitnessRequest> = { hasGenerator: next };
              if (!next) patch.generatorCapacityKva = undefined;
              onDraftChange(patch);
            }}
            disabled={readOnly}
            className={`relative inline-flex h-6 w-11 flex-shrink-0 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 disabled:opacity-50 ${
              draft.hasGenerator === true ? 'bg-primary-600' : 'bg-gray-300'
            }`}
          >
            <span
              className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                draft.hasGenerator === true ? 'translate-x-5' : 'translate-x-0.5'
              }`}
            />
          </button>
        </div>
        {draft.hasGenerator === true && (
          <Input
            label="Generator Capacity"
            type="number"
            min={1}
            step={1}
            value={draft.generatorCapacityKva ?? ''}
            onChange={(e) => {
              const raw = e.target.value;
              onDraftChange({
                generatorCapacityKva: raw === '' ? undefined : Math.max(0, Number(raw)),
              });
            }}
            disabled={readOnly}
            rightIcon={<span className="text-xs font-medium text-gray-500">kVA</span>}
            required
            error={errors.generatorCapacityKva}
          />
        )}
      </div>

      {/* 7. Inspection Interval */}
      <div className="space-y-1.5">
        <Select
          label="Inspection Interval"
          required
          value={
            draft.inspectionIntervalMonths ? String(draft.inspectionIntervalMonths) : ''
          }
          onChange={(e) => {
            const v = e.target.value;
            onDraftChange({
              inspectionIntervalMonths: (v ? Number(v) : undefined) as
                | InspectionInterval
                | undefined,
            });
          }}
          disabled={readOnly}
          options={[
            { value: '', label: 'Pick an interval' },
            { value: '6', label: '6 months' },
            { value: '12', label: '12 months' },
            { value: '24', label: '24 months' },
            { value: '36', label: '36 months' },
            { value: '60', label: '60 months' },
          ]}
          error={errors.inspectionIntervalMonths}
        />
      </div>

      {/* 8. LEW Appointment Date */}
      <div className="space-y-1.5">
        <Input
          label="LEW Appointment Date"
          type="date"
          required
          value={draft.lewAppointmentDate ?? ''}
          onChange={(e) => onDraftChange({ lewAppointmentDate: e.target.value || undefined })}
          disabled={readOnly}
          error={errors.lewAppointmentDate}
          hint="Default is today. Adjust if you were formally appointed earlier."
        />
      </div>

      {/* 9. LEW Consent Date (optional, auto-today on finalize) */}
      <div className="space-y-1.5">
        <Input
          label="LEW Consent Date"
          type="date"
          value={draft.lewConsentDate ?? ''}
          onChange={(e) => onDraftChange({ lewConsentDate: e.target.value || undefined })}
          disabled={readOnly}
          hint="Leave blank to auto-fill today's date when finalizing. Back-date is allowed if you signed earlier."
        />
      </div>

      {/* Navigation */}
      <div className="flex flex-col gap-2 pt-4 sm:flex-row sm:items-center sm:justify-between">
        <Button variant="outline" onClick={onPrevious} disabled={saving}>
          ← Previous
        </Button>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            onClick={() => void onSaveDraft()}
            loading={saving}
            disabled={readOnly}
          >
            Save Draft
          </Button>
          <Button onClick={onNext} disabled={saving || readOnly}>
            Next →
          </Button>
        </div>
      </div>
    </div>
  );
}

function badgeForHint({
  provided,
  edited,
}: {
  provided: boolean;
  edited: boolean;
}): React.ReactNode {
  if (!provided) return null;
  if (edited) {
    return <Badge variant="warning">From applicant (edited)</Badge>;
  }
  return <Badge variant="info">From applicant</Badge>;
}
