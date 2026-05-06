import type { PriceCalculation, ApplicationType } from '../../../types';
import { KvaPendingBadge } from '../../../components/applicant/KvaPendingBadge';
import { Badge } from '../../../components/ui/Badge';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
  normalizeMsslHint,
  type ConsumerType,
  type RetailerCode,
} from '../../../constants/cof';

interface FormData {
  applicationType: ApplicationType;
  spAccountNo: string;
  address: string;
  postalCode: string;
  // P2.B — EMA ELISE 5-part (optional, 없으면 legacy address 로 폴백)
  installationBlock?: string;
  installationUnit?: string;
  installationStreet?: string;
  installationBuilding?: string;
  installationPostalCode?: string;
  buildingType: string;
  selectedKva: number | null;
  /** Phase 5: "I don't know" 선택 시 true — 가격은 "From ..." 표시 */
  kvaUnknown?: boolean;
  existingLicenceNo: string;
  existingExpiryDate: string;
  renewalPeriodMonths: number | null;
  renewalReferenceNo: string;
  sldOption: 'SELF_UPLOAD' | 'SUBMIT_WITHIN_3_MONTHS' | 'REQUEST_LEW';
  // ── P2.A: Optional fast-track hint 필드 ──
  msslHint?: string;
  supplyVoltageHint?: number;
  consumerTypeHint?: ConsumerType;
  retailerHint?: RetailerCode;
  hasGeneratorHint?: boolean;
  generatorCapacityHint?: number;
}

interface StepReviewProps {
  formData: FormData;
  priceResult: PriceCalculation | null;
}

export function StepReview({ formData, priceResult }: StepReviewProps) {
  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-lg font-semibold text-gray-800">Review & Confirm</h2>
        <p className="text-sm text-gray-500 mt-1">Please review your application details before submitting</p>
      </div>

      {/* Application Type Badge */}
      <div className="flex items-center gap-2">
        <span className={`inline-flex items-center px-3 py-1 rounded-full text-sm font-medium ${
          formData.applicationType === 'RENEWAL'
            ? 'bg-orange-100 text-orange-800'
            : 'bg-blue-100 text-blue-800'
        }`}>
          {formData.applicationType === 'RENEWAL' ? '🔄 Licence Renewal' : '🏢 New Licence'}
        </span>
      </div>

      {/* P2.A — "더 빠른 처리를 위해 제공하신 정보" 요약.
          입력된 hint만 나열하며, 비어 있으면 섹션 자체를 렌더하지 않는다(부채감 제거, 스펙 §5.1 §9-17). */}
      <HintSummary formData={formData} />

      {/* Licence Period (both NEW and RENEWAL) */}
      {formData.renewalPeriodMonths && (
        <div className="bg-gray-50 rounded-lg p-4 space-y-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Licence Period</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <dt className="text-xs text-gray-500">Duration</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">
                {formData.renewalPeriodMonths} months
              </dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">EMA Fee</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">
                {priceResult?.emaFee != null && priceResult.emaFee > 0
                  ? `SGD $${priceResult.emaFee.toLocaleString()}`
                  : '—'}
              </dd>
            </div>
          </div>
        </div>
      )}

      {/* Renewal Details (if RENEWAL) */}
      {formData.applicationType === 'RENEWAL' && (
        <div className="bg-orange-50 rounded-lg p-4 space-y-3 border border-orange-100">
          <h3 className="text-sm font-semibold text-orange-700 uppercase tracking-wider">Renewal Details</h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <dt className="text-xs text-orange-600">Existing Licence No.</dt>
              <dd className="text-sm font-medium text-orange-800 mt-0.5">
                {formData.existingLicenceNo || '—'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-orange-600">Existing Expiry Date</dt>
              <dd className="text-sm font-medium text-orange-800 mt-0.5">
                {formData.existingExpiryDate || '—'}
              </dd>
            </div>
            <div className="sm:col-span-2">
              <dt className="text-xs text-orange-600">Renewal Reference No.</dt>
              <dd className="text-sm font-medium text-orange-800 mt-0.5">
                {formData.renewalReferenceNo || '—'}
              </dd>
            </div>
          </div>
        </div>
      )}

      {/* SLD Option */}
      <div className={`rounded-lg p-4 space-y-2 border ${
        formData.sldOption === 'REQUEST_LEW'
          ? 'bg-emerald-50 border-emerald-200'
          : 'bg-gray-50 border-gray-100'
      }`}>
        <h3 className={`text-sm font-semibold uppercase tracking-wider ${
          formData.sldOption === 'REQUEST_LEW' ? 'text-emerald-700' : 'text-gray-700'
        }`}>SLD (Single Line Diagram)</h3>
        <div className="flex items-center gap-2">
          <span>{formData.sldOption === 'REQUEST_LEW' ? '🔧' : '📄'}</span>
          <span className={`text-sm font-medium ${
            formData.sldOption === 'REQUEST_LEW' ? 'text-emerald-800' : 'text-gray-800'
          }`}>
            {formData.sldOption === 'REQUEST_LEW'
              ? 'LEW will prepare the SLD for you'
              : formData.sldOption === 'SUBMIT_WITHIN_3_MONTHS'
              ? 'You will submit the SLD within 3 months'
              : 'You will upload the SLD yourself'}
          </span>
        </div>
        {formData.sldOption === 'REQUEST_LEW' && (
          <p className="text-xs text-emerald-600">
            An SLD drawing request will be automatically sent to the assigned LEW after submission.
            Additional fee may apply.
          </p>
        )}
        {formData.sldOption === 'SELF_UPLOAD' && (
          <p className="text-xs text-gray-500 mt-1">
            You can upload your SLD from the application detail page after submission.
          </p>
        )}
        {formData.sldOption === 'SUBMIT_WITHIN_3_MONTHS' && (
          <p className="text-xs text-gray-500 mt-1">
            EMA allows SLD submission within 3 months of the application.
            We'll remind you as the deadline approaches.
          </p>
        )}
      </div>

      {/* Property Details — EMA ELISE 5-part 우선 표시, 없으면 legacy address 라인으로 폴백 */}
      <div className="bg-gray-50 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Installation Address</h3>
        {(formData.installationBlock || formData.installationStreet) ? (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
            <ReviewLine label="Block / House No" value={formData.installationBlock} />
            <ReviewLine label="Unit #" value={formData.installationUnit} />
            <div className="sm:col-span-2">
              <ReviewLine label="Street" value={formData.installationStreet} />
            </div>
            <ReviewLine label="Building" value={formData.installationBuilding} />
            <ReviewLine label="Postal Code" value={formData.installationPostalCode} />
            <ReviewLine label="Building Type" value={formData.buildingType} />
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <dt className="text-xs text-gray-500">Address</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">{formData.address}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Postal Code</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">{formData.postalCode}</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Building Type</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">{formData.buildingType || 'Not specified'}</dd>
            </div>
          </div>
        )}
      </div>

      {/* Capacity & Price */}
      <div className="bg-gray-50 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Capacity & Pricing</h3>
        {formData.kvaUnknown ? (
          <div className="flex items-center gap-2">
            <KvaPendingBadge />
            <span className="text-sm text-gray-600">Your LEW will confirm the kVA after reviewing your application.</span>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <dt className="text-xs text-gray-500">Selected kVA</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">{formData.selectedKva} kVA</dd>
            </div>
            <div>
              <dt className="text-xs text-gray-500">Tier</dt>
              <dd className="text-sm font-medium text-gray-800 mt-0.5">{priceResult?.tierDescription || '-'}</dd>
            </div>
          </div>
        )}
      </div>

      {/* Total with breakdown */}
      <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
        {formData.kvaUnknown ? (
          <div>
            <div className="flex items-baseline gap-2">
              <span className="text-sm text-primary-700">From</span>
              <span className="text-2xl font-bold text-primary-800 underline decoration-dashed decoration-primary-400 underline-offset-4">
                S$350
              </span>
            </div>
            <p className="text-xs text-primary-600 mt-2">
              Final price after LEW confirms your kVA.
            </p>
          </div>
        ) : priceResult && (
          <div className="space-y-2 mb-3">
            <div className="flex justify-between text-sm">
              <span className="text-primary-700">kVA Tier Price</span>
              <span className="font-medium text-primary-800">SGD ${priceResult.price.toLocaleString()}</span>
            </div>
            {priceResult.sldFee != null && priceResult.sldFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">SLD Drawing Fee</span>
                <span className="font-medium text-primary-800">SGD ${priceResult.sldFee.toLocaleString()}</span>
              </div>
            )}
            {priceResult.emaFee != null && priceResult.emaFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">EMA Fee ({formData.renewalPeriodMonths}-month)</span>
                <span className="font-medium text-primary-800">SGD ${priceResult.emaFee.toLocaleString()}</span>
              </div>
            )}
            <div className="border-t border-primary-200 pt-2"></div>
          </div>
        )}
        {!formData.kvaUnknown && (
          <>
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-primary-700">Total Amount Due</span>
              <span className="text-2xl font-bold text-primary-800">
                SGD ${priceResult?.totalAmount.toLocaleString() || '—'}
              </span>
            </div>
            <p className="text-xs text-primary-600 mt-2">
              Payment via PayNow. Details will be provided after submission.
            </p>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * P2.A — 신청자가 Optional Fast-Track 섹션에 입력한 hint만 요약 노출.
 * - 입력된 항목 0건이면 섹션 자체를 null 반환 (부재 언급 금지, 스펙 §5.1 부채감 제거).
 * - MSSL은 last4만 노출. 다른 필드는 값 그대로.
 */
function HintSummary({ formData }: { formData: FormData }) {
  // 입력된 항목 수집
  const items: Array<{ label: string; display: string }> = [];

  // MSSL — 10자리 정규화 후 last1만 표시 (백엔드 last4 정책과 동일 UX: "•••-••-••••-D")
  const msslNormalized = normalizeMsslHint(formData.msslHint);
  const msslDigits = (formData.msslHint ?? '').replace(/\D/g, '');
  if (msslDigits.length > 0) {
    // 10자리 완성 시 일관 마스킹. 부분 입력은 raw digits의 마지막 1자리만 노출.
    const lastDigit = msslDigits.slice(-1);
    const masked = msslNormalized && msslDigits.length === 10
      ? `•••-••-••••-${lastDigit}`
      : `•••-${lastDigit}`;
    items.push({ label: 'MSSL Account No', display: masked });
  }

  if (formData.consumerTypeHint) {
    const opt = CONSUMER_TYPE_OPTIONS.find((o) => o.value === formData.consumerTypeHint);
    if (opt) items.push({ label: 'Consumer Type', display: opt.label });
  }

  if (formData.retailerHint) {
    const opt = RETAILER_OPTIONS.find((o) => o.value === formData.retailerHint);
    if (opt) items.push({ label: 'Retailer', display: opt.label });
  }

  if (formData.supplyVoltageHint) {
    const opt = SUPPLY_VOLTAGE_OPTIONS.find((o) => o.value === formData.supplyVoltageHint);
    items.push({ label: 'Supply Voltage', display: opt ? opt.label : `${formData.supplyVoltageHint}V` });
  }

  if (formData.hasGeneratorHint) {
    const capDisplay =
      formData.generatorCapacityHint != null
        ? `Yes — ${formData.generatorCapacityHint} kVA`
        : 'Yes';
    items.push({ label: 'Generator', display: capDisplay });
  }

  if (items.length === 0) {
    // 미입력 섹션은 완전히 숨김 — 미입력 항목을 화면 어디에도 언급하지 않는다(스펙 §9-17).
    return null;
  }

  return (
    <div className="bg-blue-50 rounded-lg p-4 space-y-3 border border-blue-100">
      <h3 className="text-sm font-semibold text-blue-700 uppercase tracking-wider">
        Details you shared to speed up review
      </h3>
      <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {items.map((it) => (
          <div key={it.label}>
            <dt className="text-xs text-blue-600 flex items-center gap-1.5">
              <Badge variant="info">Provided</Badge>
              <span>{it.label}</span>
            </dt>
            <dd className="text-sm font-medium text-blue-800 mt-1">{it.display}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/** 리뷰 화면 5-part 주소 라인 (빈 값은 — 로 표시). */
function ReviewLine({ label, value }: { label: string; value?: string }) {
  return (
    <div>
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd className="text-sm font-medium text-gray-800 mt-0.5">
        {value && value.trim() ? value : '—'}
      </dd>
    </div>
  );
}
