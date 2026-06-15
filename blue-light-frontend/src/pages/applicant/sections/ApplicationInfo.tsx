import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { Select } from '../../../components/ui/Select';
import { Badge } from '../../../components/ui/Badge';
import { InfoField } from '../../../components/common/InfoField';
import { KvaPendingBadge } from '../../../components/applicant/KvaPendingBadge';
import {
  AddressInputGroup,
  hasAnyAddressPart,
  type AddressInputValues,
} from '../../../components/domain/AddressInputGroup';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
} from '../../../constants/cof';
import type { Application, MasterPrice } from '../../../types';

interface EditState {
  address: string;
  postalCode: string;
  buildingType: string;
  kva: number;
  price: number | null;
  /** P2.B — EMA 5-part 입력 값 (수정 모드에서만). */
  installation: AddressInputValues;
}

interface ApplicationInfoProps {
  application: Application;
  editMode: boolean;
  editState: EditState;
  prices: MasterPrice[];
  submitting: boolean;
  onEditStateChange: (field: keyof EditState, value: string | number | AddressInputValues) => void;
  onKvaChange: (kva: number) => void;
  onResubmit: () => void;
  onCancelEdit: () => void;
}

export function ApplicationInfo({
  application,
  editMode,
  editState,
  prices,
  submitting,
  onEditStateChange,
  onKvaChange,
  onResubmit,
  onCancelEdit,
}: ApplicationInfoProps) {
  return (
    <>
      {/* Property Details */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Property Details</h2>

        {editMode ? (
          <div className="space-y-4">
            <AddressInputGroup
              title="Installation Address"
              description="EMA ELISE renewal form: Block / Unit / Street / Building / Postal."
              values={editState.installation}
              onChange={(next) => onEditStateChange('installation', next)}
              required
            />
            <Input
              label="Building Type"
              maxLength={50}
              value={editState.buildingType}
              onChange={(e) => onEditStateChange('buildingType', e.target.value)}
            />
            <Select
              label="Electric Box (kVA)"
              required
              value={String(editState.kva)}
              onChange={(e) => onKvaChange(Number(e.target.value))}
              options={prices.map((p) => ({
                value: String(p.kvaMin),
                label: `${p.kvaMin} kVA — SGD $${p.price.toLocaleString()}`,
              }))}
              placeholder="Select kVA"
            />
            {editState.price !== null && (
              <div className="bg-primary-50 rounded-lg p-3 border border-primary-100">
                <p className="text-sm text-primary-700">
                  Updated Quote: <span className="font-bold">SGD ${editState.price.toLocaleString()}</span>
                </p>
              </div>
            )}
            <div className="flex gap-3 pt-2">
              <Button
                onClick={onResubmit}
                loading={submitting}
                disabled={
                  !editState.installation.block.trim() ||
                  !editState.installation.street.trim() ||
                  !editState.installation.postalCode.trim() ||
                  !editState.kva
                }
              >
                Resubmit Application
              </Button>
              <Button variant="outline" onClick={onCancelEdit}>
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <ReadOnlyInstallationDetails application={application} />
        )}
      </Card>

      {/* 제출 내역 전체 요약 — 신청 폼에서 입력했지만 위에 안 보이는 항목들을 다시 볼 수 있게. (읽기 모드 전용) */}
      {!editMode && <ApplicationSummaryDetails application={application} />}

      {/* Licence Period (both NEW and RENEWAL) */}
      {application.renewalPeriodMonths && (
        <Card>
          <h2 className="text-lg font-semibold text-gray-800 mb-4">Licence Period</h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <InfoField label="Duration" value={`${application.renewalPeriodMonths} months`} />
            <InfoField
              label="EMA Fee"
              value={application.emaFee ? `SGD $${application.emaFee.toLocaleString()}` : '—'}
            />
          </div>
        </Card>
      )}

      {/* Renewal Details (RENEWAL only) */}
      {application.applicationType === 'RENEWAL' && (
        <Card>
          <h2 className="text-lg font-semibold text-gray-800 mb-4">Renewal Details</h2>
          <div className="bg-orange-50 rounded-lg p-4 border border-orange-100">
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoField label="Existing Licence No." value={application.existingLicenceNo || '—'} />
              <InfoField label="Existing Expiry Date" value={application.existingExpiryDate || '—'} />
              {application.renewalReferenceNo && (
                <InfoField label="Renewal Reference No." value={application.renewalReferenceNo} />
              )}
              {application.originalApplicationSeq && (
                <InfoField label="Original Application" value={`#${application.originalApplicationSeq}`} />
              )}
            </div>
          </div>
        </Card>
      )}
    </>
  );
}

/**
 * 읽기 모드 Installation Address 렌더.
 *
 * - 5-part 중 하나라도 있으면 EMA ELISE 양식 순서대로 5 줄 표시.
 * - 모두 비어 있으면 legacy 단일 `application.address` / postalCode 를 2열로 폴백.
 */
function ReadOnlyInstallationDetails({ application }: { application: Application }) {
  const fiveParts: AddressInputValues = {
    block: application.installationAddressBlock ?? '',
    unit: application.installationAddressUnit ?? '',
    street: application.installationAddressStreet ?? '',
    building: application.installationAddressBuilding ?? '',
    postalCode: application.installationAddressPostalCode ?? '',
  };
  const hasFiveParts = hasAnyAddressPart(fiveParts);

  // JSX 변수로 보관 (props 없는 단순 JSX) — 컴포넌트 함수 안에서 컴포넌트를 정의하면
  // react-hooks/static-components 규칙 위반 + 매 렌더링마다 새 컴포넌트 식별자가 생성되어
  // 자식 트리가 unmount/remount 된다.
  const kvaLine = (
    <div>
      <dt className="text-xs text-gray-500 mb-0.5">Electric Box (kVA)</dt>
      {application.kvaStatus !== 'CONFIRMED' && application.kvaSource === 'USER_INPUT' ? (
        // 신청자가 직접 신고한 값 — 아직 LEW 미확정. 신고값을 보여주되 "확정 대기" 표시.
        <dd className="text-sm font-medium text-gray-800 space-y-1">
          <div className="flex items-center gap-2">
            <span>{application.selectedKva} kVA</span>
            <KvaPendingBadge label="pending LEW confirmation" />
          </div>
          <p className="text-xs text-gray-500">
            You entered this value. Your LEW will verify and confirm it (the price may change).
          </p>
        </dd>
      ) : application.kvaStatus === 'UNKNOWN' ? (
        <div className="space-y-1">
          <KvaPendingBadge label="kVA pending LEW review" />
          <p className="text-xs text-gray-500">
            Your LEW will confirm the kVA based on your main breaker or SP account information.
          </p>
        </div>
      ) : (
        <dd className="text-sm font-medium text-gray-800 flex items-center gap-2">
          <span>{application.selectedKva} kVA</span>
          {application.kvaSource === 'LEW_VERIFIED' && (
            <Badge variant="success">Confirmed by LEW</Badge>
          )}
        </dd>
      )}
    </div>
  );

  if (!hasFiveParts) {
    return (
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <InfoField label="Installation Address" value={application.address} />
        <InfoField label="Postal Code" value={application.postalCode} />
        <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
        {kvaLine}
        {application.spAccountNo && (
          <InfoField label="SP Account No." value={application.spAccountNo} />
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <InfoField label="Block / House No" value={fiveParts.block || '—'} />
        <InfoField label="Unit #" value={fiveParts.unit || '—'} />
        <div className="sm:col-span-2">
          <InfoField label="Street" value={fiveParts.street || '—'} />
        </div>
        <InfoField label="Building" value={fiveParts.building || '—'} />
        <InfoField label="Postal Code" value={fiveParts.postalCode || application.postalCode || '—'} />
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-4 border-t border-gray-100">
        <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
        {kvaLine}
        {application.spAccountNo && (
          <InfoField label="SP Account No." value={application.spAccountNo} />
        )}
      </div>
    </div>
  );
}

const PREMISES_TYPE_LABELS: Record<string, string> = {
  COMMERCIAL: 'Commercial',
  FACTORIES: 'Factories',
  FARM: 'Farm',
  RESIDENTIAL: 'Residential',
  INDUSTRIAL: 'Industrial',
  HOTEL: 'Hotel',
  HEALTHCARE: 'Healthcare',
  EDUCATION: 'Education',
  GOVERNMENT: 'Government',
  MIXED_USE: 'Mixed use',
  OTHER: 'Other',
};

const SLD_OPTION_LABELS: Record<string, string> = {
  SELF_UPLOAD: 'Upload my own SLD',
  SUBMIT_WITHIN_3_MONTHS: 'Submit within 3 months',
  REQUEST_LEW: 'Request LEW to prepare',
};

function labelFromOptions(
  options: ReadonlyArray<{ value: string | number; label: string }>,
  value: string | number | undefined,
): string | undefined {
  if (value === undefined || value === null) return undefined;
  return options.find((o) => o.value === value)?.label;
}

/**
 * 신청 폼에서 입력했지만 Property Details 에 표시되지 않는 항목들을 모아 보여주는 요약 카드.
 *
 * <p>값이 있는 필드만 렌더 — 빈 항목으로 화면을 채우지 않는다. 라벨은 신청 폼과 동일한
 * 옵션 상수({@code constants/cof.ts})에서 가져와 표기를 일치시킨다.</p>
 */
function ApplicationSummaryDetails({ application }: { application: Application }) {
  const rows: { label: string; value: string }[] = [];
  const push = (label: string, value?: string | number | null) => {
    if (value !== undefined && value !== null && value !== '') {
      rows.push({ label, value: String(value) });
    }
  };

  push('Application Type', application.applicationType === 'RENEWAL' ? 'Renewal' : 'New');
  push(
    'Applicant Type',
    application.applicantType === 'CORPORATE'
      ? 'Corporate'
      : application.applicantType === 'INDIVIDUAL'
        ? 'Individual'
        : undefined,
  );
  push('Installation Name', application.installationName);
  push(
    'Premises Type',
    application.premisesType
      ? (PREMISES_TYPE_LABELS[application.premisesType] ?? application.premisesType)
      : undefined,
  );
  if (application.isRentalPremises) {
    push('Rental Premises', 'Yes');
    push('Landlord EI Licence', application.landlordEiLicenceMasked);
  }
  push('Consumer Type', labelFromOptions(CONSUMER_TYPE_OPTIONS, application.consumerTypeHint));
  push('Electricity Retailer', labelFromOptions(RETAILER_OPTIONS, application.retailerHint));
  push('Supply Voltage', labelFromOptions(SUPPLY_VOLTAGE_OPTIONS, application.supplyVoltageHint));
  if (application.hasGeneratorHint !== undefined && application.hasGeneratorHint !== null) {
    push('Standby Generator', application.hasGeneratorHint ? 'Yes' : 'No');
    if (application.hasGeneratorHint && application.generatorCapacityHint) {
      push('Generator Capacity', `${application.generatorCapacityHint} kVA`);
    }
  }
  push('MSSL No. (last 4)', application.msslHintLast4 ? `••••${application.msslHintLast4}` : undefined);
  push(
    'SLD Submission',
    application.sldOption
      ? (SLD_OPTION_LABELS[application.sldOption] ?? application.sldOption)
      : undefined,
  );

  if (rows.length === 0) return null;

  return (
    <Card>
      <h2 className="text-lg font-semibold text-gray-800 mb-4">Application Summary</h2>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
        {rows.map((r) => (
          <InfoField key={r.label} label={r.label} value={r.value} />
        ))}
      </div>
    </Card>
  );
}
