import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Card } from '../../../components/ui/Card';
import { InfoBox } from '../../../components/ui/InfoBox';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
} from '../../../constants/cof';
import type { CertificateOfFitnessRequest } from '../../../types/cof';

/**
 * Step 3 — Review & Finalize.
 *
 * LEW에게는 모든 CoF 값이 평문으로 노출된다. 동시에 "신청자가 보게 될 화면" 미리보기 박스를
 * 제공하여 마스킹 결과를 확인시킨다 (스펙 §6 Step 3).
 */
export interface CofStepReviewFinalizeProps {
  draft: CertificateOfFitnessRequest;
  confirmed: boolean;
  onConfirmedChange: (next: boolean) => void;
  onPrevious: () => void;
  onSaveDraft: () => Promise<void> | void;
  onFinalize: () => Promise<void> | void;
  saving: boolean;
  finalizing: boolean;
  readOnly?: boolean;
}

export function CofStepReviewFinalize({
  draft,
  confirmed,
  onConfirmedChange,
  onPrevious,
  onSaveDraft,
  onFinalize,
  saving,
  finalizing,
  readOnly = false,
}: CofStepReviewFinalizeProps) {
  const finalizeDisabled = !confirmed || readOnly || finalizing;

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-lg font-semibold text-gray-800">Review & Finalize</h2>
        <p className="text-sm text-gray-500 mt-1">
          Confirm each field one more time. Finalizing moves the application to payment stage and
          cannot be edited afterwards.
        </p>
      </div>

      {/* LEW-visible full plain summary */}
      <Card>
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">
            Certificate of Fitness (LEW view)
          </h3>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Field label="MSSL Account No" value={draft.msslAccountNo} mono />
            <Field label="Consumer Type" value={labelForConsumer(draft.consumerType)} />
            <Field label="Retailer" value={labelForRetailer(draft.retailerCode)} />
            <Field label="Supply Voltage" value={labelForVoltage(draft.supplyVoltageV)} />
            <Field
              label="Approved Load"
              value={draft.approvedLoadKva != null ? `${draft.approvedLoadKva} kVA` : undefined}
            />
            <Field
              label="Generator"
              value={
                draft.hasGenerator
                  ? `Yes — ${draft.generatorCapacityKva ?? '?'} kVA`
                  : 'No'
              }
            />
            <Field
              label="Inspection Interval"
              value={
                draft.inspectionIntervalMonths
                  ? `${draft.inspectionIntervalMonths} months`
                  : undefined
              }
            />
            <Field label="LEW Appointment Date" value={draft.lewAppointmentDate} />
            <Field
              label="LEW Consent Date"
              value={draft.lewConsentDate ?? 'Will default to today on finalize'}
            />
          </dl>
        </div>
      </Card>

      {/* Applicant-visible preview (masked) */}
      <Card className="bg-blue-50">
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-blue-700 uppercase tracking-wider">
              What the applicant will see
            </h3>
            <Badge variant="info">Masked preview</Badge>
          </div>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <Field
              label="MSSL Account No"
              value={maskMssl(draft.msslAccountNo)}
              mono
              tone="info"
            />
            <Field
              label="Consumer Type"
              value={labelForConsumer(draft.consumerType)}
              tone="info"
            />
            <Field
              label="Retailer"
              value={labelForRetailer(draft.retailerCode)}
              tone="info"
            />
            <Field
              label="Supply Voltage"
              value={labelForVoltage(draft.supplyVoltageV)}
              tone="info"
            />
            <Field
              label="Approved Load"
              value={draft.approvedLoadKva != null ? `${draft.approvedLoadKva} kVA` : undefined}
              tone="info"
            />
            <Field
              label="Inspection Interval"
              value={
                draft.inspectionIntervalMonths
                  ? `${draft.inspectionIntervalMonths} months`
                  : undefined
              }
              tone="info"
            />
            {/* Generator capacity는 신청자 화면에 노출되지 않음 (스펙 §3.5). */}
          </dl>
          <p className="text-xs text-blue-700">
            Generator capacity and LEW signature dates are omitted from the applicant view by
            policy.
          </p>
        </div>
      </Card>

      {/* Declaration */}
      <div className="border-2 border-gray-200 rounded-lg p-4 bg-white">
        <label className="flex items-start gap-3 cursor-pointer">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => onConfirmedChange(e.target.checked)}
            disabled={readOnly || finalizing}
            className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
          />
          <span className="text-sm text-gray-700">
            I confirm that the Certificate of Fitness above complies with EMA regulations and I
            am signing it as the Licensed Electrical Worker for this installation.
          </span>
        </label>
      </div>

      {readOnly && (
        <InfoBox variant="info">
          This Certificate of Fitness has already been finalized. You can review the values but
          can no longer edit or re-finalize it.
        </InfoBox>
      )}

      {/* Navigation */}
      <div className="flex flex-col gap-2 pt-2 sm:flex-row sm:items-center sm:justify-between">
        <Button variant="outline" onClick={onPrevious} disabled={finalizing}>
          ← Previous
        </Button>
        <div className="flex gap-2">
          <Button
            variant="secondary"
            onClick={() => void onSaveDraft()}
            loading={saving}
            disabled={readOnly || finalizing}
          >
            Save Draft
          </Button>
          <Button
            onClick={() => void onFinalize()}
            loading={finalizing}
            disabled={finalizeDisabled}
            aria-disabled={finalizeDisabled}
          >
            Finalize & Submit
          </Button>
        </div>
      </div>
    </div>
  );
}

function Field({
  label,
  value,
  mono,
  tone = 'default',
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
  tone?: 'default' | 'info';
}) {
  const display = value == null || value === '' ? '—' : String(value);
  const labelColor = tone === 'info' ? 'text-blue-600' : 'text-gray-500';
  const valueColor = tone === 'info' ? 'text-blue-900' : 'text-gray-800';
  return (
    <div>
      <dt className={`text-xs ${labelColor}`}>{label}</dt>
      <dd className={`mt-0.5 text-sm font-medium ${valueColor} ${mono ? 'font-mono' : ''}`}>
        {display}
      </dd>
    </div>
  );
}

function labelForConsumer(v?: string): string | undefined {
  if (!v) return undefined;
  return CONSUMER_TYPE_OPTIONS.find((o) => o.value === v)?.label ?? v;
}
function labelForRetailer(v?: string): string | undefined {
  if (!v) return undefined;
  return RETAILER_OPTIONS.find((o) => o.value === v)?.label ?? v;
}
function labelForVoltage(v?: number): string | undefined {
  if (v == null) return undefined;
  return SUPPLY_VOLTAGE_OPTIONS.find((o) => o.value === v)?.label ?? `${v}V`;
}

/**
 * 신청자 관점 MSSL 마스킹 (last4만 노출).
 * 입력값이 완전한 10자리 포맷이면 `•••-••-••••-XXXX` 형태.
 * 불완전하면 원문 last4만 추출해 대시 없이 표시.
 */
function maskMssl(value?: string): string | undefined {
  if (!value) return undefined;
  const digits = value.replace(/\D/g, '');
  if (digits.length === 0) return undefined;
  const last4 = digits.slice(-4);
  if (digits.length === 10) {
    return `•••-••-••••-${last4.charAt(last4.length - 1)}`;
  }
  return `••••${last4}`;
}
