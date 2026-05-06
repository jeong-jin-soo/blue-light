import { useCallback, useEffect, useState } from 'react';
import { Select } from '../ui/Select';
import { Input } from '../ui/Input';
import { InfoBox } from '../ui/InfoBox';
import { MsslAccountInput } from '../domain/MsslAccountInput';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
  type ConsumerType,
  type RetailerCode,
} from '../../constants/cof';

/**
 * 신청자 New Application Step 3 상단에 배치되는 선택적 fast-track 섹션.
 *
 * 스펙 §5 요구사항:
 * - 어떤 필드도 필수 표시(`*`, "required", "필수") 금지.
 * - 기본 접힘 상태 — 사용자가 펼치지 않고도 신청 가능.
 * - 접힘/펼침 상태는 localStorage로 연속성 유지.
 * - 이득 부각형 카피만 사용 ("더 빠른 처리", "아는 항목만").
 *
 * Props는 NewApplicationPage의 FormData 중 P2.A hint 관련 필드만 구독.
 */

const LOCAL_STORAGE_KEY = 'optional-fasttrack-expanded';

export interface OptionalFastTrackFormSlice {
  msslHint: string;
  supplyVoltageHint?: number;
  consumerTypeHint?: ConsumerType;
  retailerHint?: RetailerCode;
  hasGeneratorHint: boolean;
  generatorCapacityHint?: number;
}

/**
 * updateField 시그니처 — 부모(NewApplicationPage.tsx 등)가 더 큰 FormData에 대한
 * 제네릭 setter를 갖고 있을 수 있으므로 하위 타입 호환 위해 `any` 대신
 * 슬라이스 필드로 한정된 호출 패턴만 반영한다. 실제 호출 시 키는 본 컴포넌트가
 * 제어하는 슬라이스 키에 한정되므로 부모 쪽 제네릭이 그대로 만족된다.
 */
type FastTrackUpdateField = <K extends keyof OptionalFastTrackFormSlice>(
  field: K,
  value: OptionalFastTrackFormSlice[K],
) => void;

export interface OptionalFastTrackSectionProps {
  formData: OptionalFastTrackFormSlice;
  /** 변경할 필드를 한 건씩 업데이트 — NewApplicationPage.updateField와 호환. */
  updateField: FastTrackUpdateField;
}

function readExpandedFromStorage(): boolean {
  if (typeof window === 'undefined') return false;
  try {
    const raw = window.localStorage.getItem(LOCAL_STORAGE_KEY);
    return raw === 'true';
  } catch {
    return false;
  }
}

function writeExpandedToStorage(value: boolean) {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(LOCAL_STORAGE_KEY, value ? 'true' : 'false');
  } catch {
    /* ignore storage failures */
  }
}

export function OptionalFastTrackSection({ formData, updateField }: OptionalFastTrackSectionProps) {
  const [expanded, setExpanded] = useState<boolean>(() => readExpandedFromStorage());

  useEffect(() => {
    writeExpandedToStorage(expanded);
  }, [expanded]);

  const toggle = useCallback(() => setExpanded((prev) => !prev), []);

  // Retailer는 Consumer Type이 CONTESTABLE일 때만 활성화.
  // Non-contestable인 경우 드롭다운에 SP Services 고정 라벨만 보여준다.
  const retailerDisabled = formData.consumerTypeHint !== 'CONTESTABLE';
  const retailerValue = retailerDisabled
    ? 'SP_SERVICES_LIMITED'
    : (formData.retailerHint ?? '');

  if (!expanded) {
    return (
      <div
        className="relative overflow-hidden rounded-lg border border-blue-200 bg-gradient-to-r from-blue-50 via-blue-50 to-white p-4"
        role="region"
        aria-label="Fast-track details to speed up LEW review"
      >
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-start gap-3">
            <div
              className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-700"
              aria-hidden="true"
            >
              <svg className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth={2} viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
              </svg>
            </div>
            <div>
              <h3 className="text-sm font-semibold text-blue-900">
                Know a few details? Help LEW review faster
              </h3>
              <p className="mt-0.5 text-xs text-blue-700 leading-relaxed">
                If you already know these, LEW can skip a few site questions. It's fine to leave
                everything blank — you can still submit.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={toggle}
            className="flex-shrink-0 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium text-blue-700 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-blue-300"
            aria-expanded="false"
          >
            Open →
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      className="rounded-lg border border-blue-200 bg-white p-5 space-y-5"
      role="region"
      aria-label="Fast-track details to speed up LEW review"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-base font-semibold text-gray-800">Help LEW review faster</h3>
          <p className="mt-0.5 text-xs text-gray-500">
            Fill only what you already know — each item shortens LEW's site check.
          </p>
        </div>
        <button
          type="button"
          onClick={toggle}
          className="flex-shrink-0 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium text-gray-600 hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-gray-300"
          aria-expanded="true"
        >
          Hide
        </button>
      </div>

      <InfoBox>
        Fill only the items you already know — anything left blank will be collected by your LEW
        during the site check.
      </InfoBox>

      {/* 1. MSSL Account No */}
      <div className="space-y-1.5">
        <label
          htmlFor="mssl-part-0"
          className="block text-sm font-medium text-gray-700"
        >
          MSSL Account No
        </label>
        <MsslAccountInput
          value={formData.msslHint}
          onChange={(v) => updateField('msslHint', v)}
          ariaLabel="MSSL Account Number"
        />
        <p className="text-xs text-gray-500">
          Printed at the top of your SP electricity bill. If you don't have it handy, leave it blank.
        </p>
      </div>

      {/* 2. Consumer Type */}
      <fieldset className="space-y-2">
        <legend className="block text-sm font-medium text-gray-700">Consumer Type</legend>
        <div className="space-y-2">
          {CONSUMER_TYPE_OPTIONS.map((opt) => {
            const checked = formData.consumerTypeHint === opt.value;
            return (
              <label
                key={opt.value}
                className={`flex cursor-pointer items-start gap-3 rounded-lg border p-3 transition-colors ${
                  checked
                    ? 'border-primary-500 bg-primary-50'
                    : 'border-gray-200 hover:border-gray-300'
                }`}
              >
                <input
                  type="radio"
                  name="consumerTypeHint"
                  value={opt.value}
                  checked={checked}
                  onChange={() => {
                    updateField('consumerTypeHint', opt.value);
                    // Contestable 해제 시 retailerHint 초기화 (SP 고정이 되도록)
                    if (opt.value === 'NON_CONTESTABLE') {
                      updateField('retailerHint', undefined);
                    }
                  }}
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
        <p className="text-xs text-gray-500">
          Not sure which tariff plan you're on? Leave this blank.
        </p>
      </fieldset>

      {/* 3. Retailer */}
      <div className="space-y-1.5">
        <Select
          label="Retailer"
          value={retailerValue}
          onChange={(e) =>
            updateField(
              'retailerHint',
              (e.target.value || undefined) as RetailerCode | undefined,
            )
          }
          disabled={retailerDisabled}
          options={
            retailerDisabled
              ? [{ value: 'SP_SERVICES_LIMITED', label: 'SP Services Limited' }]
              : [
                  { value: '', label: 'Pick your retailer' },
                  ...RETAILER_OPTIONS.filter((o) => o.value !== 'SP_SERVICES_LIMITED').map((o) => ({
                    value: o.value,
                    label: o.label,
                  })),
                  { value: 'SP_SERVICES_LIMITED', label: 'SP Services Limited' },
                ]
          }
          hint={
            retailerDisabled
              ? 'Non-contestable supply is delivered by SP Services.'
              : 'The retailer printed on your contract.'
          }
        />
      </div>

      {/* 4. Supply Voltage */}
      <div className="space-y-1.5">
        <Select
          label="Supply Voltage"
          value={formData.supplyVoltageHint ? String(formData.supplyVoltageHint) : ''}
          onChange={(e) => {
            const v = e.target.value;
            updateField('supplyVoltageHint', v ? Number(v) : undefined);
          }}
          options={[
            { value: '', label: 'Pick a voltage' },
            ...SUPPLY_VOLTAGE_OPTIONS.map((o) => ({ value: String(o.value), label: o.label })),
          ]}
          hint="Most homes are 230V; three-phase factories use 400V."
        />
      </div>

      {/* 5. Generator Toggle */}
      <div className="space-y-3">
        <label
          className="flex cursor-pointer items-center justify-between gap-3"
          aria-label="Generator on site"
        >
          <span>
            <span className="block text-sm font-medium text-gray-700">Generator on site</span>
            <span className="mt-0.5 block text-xs text-gray-500">
              Turn on if you have a backup generator — LEW will confirm the rating.
            </span>
          </span>
          <button
            type="button"
            role="switch"
            aria-checked={formData.hasGeneratorHint}
            onClick={() => {
              const next = !formData.hasGeneratorHint;
              updateField('hasGeneratorHint', next);
              if (!next) {
                updateField('generatorCapacityHint', undefined);
              }
            }}
            className={`relative inline-flex h-6 w-11 flex-shrink-0 items-center rounded-full transition-colors focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2 ${
              formData.hasGeneratorHint ? 'bg-primary-600' : 'bg-gray-300'
            }`}
          >
            <span
              className={`inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform ${
                formData.hasGeneratorHint ? 'translate-x-5' : 'translate-x-0.5'
              }`}
            />
          </button>
        </label>

        {/* 6. Generator Capacity */}
        {formData.hasGeneratorHint && (
          <Input
            label="Generator Capacity"
            type="number"
            min={0}
            step={1}
            placeholder="e.g. 50"
            value={formData.generatorCapacityHint ?? ''}
            onChange={(e) => {
              const raw = e.target.value;
              updateField(
                'generatorCapacityHint',
                raw === '' ? undefined : Math.max(0, Number(raw)),
              );
            }}
            hint="Rated output in kVA. Skip if you don't know — LEW will confirm."
            rightIcon={<span className="text-xs font-medium text-gray-500">kVA</span>}
          />
        )}
      </div>

      <div className="flex justify-end pt-2">
        <button
          type="button"
          onClick={toggle}
          className="text-sm font-medium text-gray-500 hover:text-gray-700"
        >
          Hide section
        </button>
      </div>
    </div>
  );
}
