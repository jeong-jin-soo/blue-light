import type { LewApplicationResponse } from '../../../types/cof';
import { Badge } from '../../../components/ui/Badge';
import { Button } from '../../../components/ui/Button';
import { Card } from '../../../components/ui/Card';

/**
 * Step 1 — Application Summary (Read-only).
 *
 * 신청자가 입력한 값 전체를 LEW에게 그대로 노출한다.
 * Correspondence Address·Landlord EI Licence 는 LEW 전용 평문.
 */
export interface CofStepApplicationSummaryProps {
  data: LewApplicationResponse;
  onNext: () => void;
}

export function CofStepApplicationSummary({ data, onNext }: CofStepApplicationSummaryProps) {
  const app = data.application;

  // Installation 5-part — EMA 양식 순서 (Block / Unit / Street / Building / Postal).
  // 하나라도 값이 있으면 5-line breakdown 을 렌더, 모두 비어 있으면 legacy 단일 address 폴백.
  const installation5 = {
    block: app.installationAddressBlock ?? '',
    unit: app.installationAddressUnit ?? '',
    street: app.installationAddressStreet ?? '',
    building: app.installationAddressBuilding ?? '',
    postalCode: app.installationAddressPostalCode ?? '',
  };
  const hasInstallation5 = Object.values(installation5).some(
    (v) => v && v.trim().length > 0,
  );

  // Correspondence 5-part — LEW 전용 평문 4 필드 + postalCode 는 plain 이 아니어도 ApplicationResponse 에 있음
  const correspondence5 = {
    block: data.correspondenceAddressBlockPlain ?? '',
    unit: data.correspondenceAddressUnitPlain ?? '',
    street: data.correspondenceAddressStreetPlain ?? '',
    building: data.correspondenceAddressBuildingPlain ?? '',
    postalCode: app.correspondenceAddressPostalCode ?? '',
  };
  const hasCorrespondence5 = Object.values(correspondence5).some(
    (v) => v && v.trim().length > 0,
  );

  return (
    <div className="space-y-5">
      <div>
        <h2 className="text-lg font-semibold text-gray-800">Application Summary</h2>
        <p className="text-sm text-gray-500 mt-1">
          Everything the applicant submitted. Review carefully before entering the Certificate of
          Fitness details.
        </p>
      </div>

      {/* Installation */}
      <Card>
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">
              Installation
            </h3>
            <Badge variant={app.applicationType === 'RENEWAL' ? 'warning' : 'info'}>
              {app.applicationType === 'RENEWAL' ? 'Renewal' : 'New'}
            </Badge>
          </div>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <SummaryField label="Installation Name" value={app.installationName} />
            <SummaryField label="Premises Type" value={app.premisesType} />
            <SummaryField label="Building Type" value={app.buildingType} />
            <SummaryField
              label="Rental Premises"
              value={app.isRentalPremises ? 'Yes' : 'No'}
            />
          </dl>
          {/* EMA ELISE 양식 준수: 5-part breakdown (Block → Unit → Street → Building → Postal).
              5-part 가 저장돼 있지 않은 legacy 신청은 단일 address 를 그대로 표시한다. */}
          <div className="border-t border-gray-100 pt-3 mt-2">
            <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Installation Address
            </h4>
            {hasInstallation5 ? (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <SummaryField label="Block / House No" value={installation5.block} />
                <SummaryField label="Unit #" value={installation5.unit} />
                <SummaryField label="Street" value={installation5.street} full />
                <SummaryField label="Building" value={installation5.building} />
                <SummaryField
                  label="Postal Code"
                  value={installation5.postalCode || app.postalCode}
                />
              </dl>
            ) : (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <SummaryField label="Address (legacy)" value={app.address} full />
                <SummaryField label="Postal Code" value={app.postalCode} />
              </dl>
            )}
          </div>
        </div>
      </Card>

      {/* Applicant / Correspondence (LEW 전용 평문) */}
      <Card>
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">
            Applicant & Correspondence
          </h3>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <SummaryField label="Applicant Type" value={app.applicantType} />
          </dl>
          {/* Correspondence — EMA 양식 순서 5-part. 값 없으면 "동일" 문구로 폴백. */}
          <div className="border-t border-gray-100 pt-3 mt-2">
            <h4 className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Correspondence Address
            </h4>
            {hasCorrespondence5 ? (
              <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <SummaryField label="Block / House No" value={correspondence5.block} />
                <SummaryField label="Unit #" value={correspondence5.unit} />
                <SummaryField label="Street" value={correspondence5.street} full />
                <SummaryField label="Building" value={correspondence5.building} />
                <SummaryField label="Postal Code" value={correspondence5.postalCode} />
              </dl>
            ) : (
              <p className="text-sm text-gray-500 italic">
                Same as installation address.
              </p>
            )}
          </div>
          {data.landlordEiLicenceNo && (
            <div className="border-t border-gray-100 pt-3 mt-2">
              <dl>
                <SummaryField
                  label="Landlord EI Licence (plain, LEW-only)"
                  value={data.landlordEiLicenceNo}
                  emphasis
                  full
                />
              </dl>
            </div>
          )}
        </div>
      </Card>

      {/* Capacity / SLD / Renewal */}
      <Card>
        <div className="space-y-3">
          <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">
            Capacity & SLD
          </h3>
          <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <SummaryField
              label="Selected kVA (applicant estimate)"
              value={
                app.kvaStatus === 'UNKNOWN'
                  ? 'UNKNOWN — LEW to confirm'
                  : `${app.selectedKva} kVA`
              }
            />
            <SummaryField label="SLD Option" value={formatSldOption(app.sldOption)} />
            {app.applicationType === 'RENEWAL' && (
              <>
                <SummaryField label="Existing Licence No" value={app.existingLicenceNo} />
                <SummaryField label="Existing Expiry Date" value={app.existingExpiryDate} />
                <SummaryField
                  label="Renewal Period"
                  value={
                    app.renewalPeriodMonths ? `${app.renewalPeriodMonths} months` : undefined
                  }
                />
                <SummaryField label="Renewal Ref. No" value={app.renewalReferenceNo} />
              </>
            )}
          </dl>
        </div>
      </Card>

      <div className="flex justify-end pt-2">
        <Button onClick={onNext}>Next →</Button>
      </div>
    </div>
  );
}

function SummaryField({
  label,
  value,
  full,
  emphasis,
}: {
  label: string;
  value?: string | number | null;
  full?: boolean;
  emphasis?: boolean;
}) {
  const display = value == null || value === '' ? '—' : String(value);
  return (
    <div className={full ? 'sm:col-span-2' : ''}>
      <dt className="text-xs text-gray-500">{label}</dt>
      <dd
        className={`mt-0.5 text-sm ${
          emphasis ? 'font-semibold text-amber-800' : 'font-medium text-gray-800'
        }`}
      >
        {display}
      </dd>
    </div>
  );
}

function formatSldOption(value?: string): string | undefined {
  if (!value) return undefined;
  switch (value) {
    case 'SELF_UPLOAD':
      return 'Applicant uploads SLD';
    case 'SUBMIT_WITHIN_3_MONTHS':
      return 'Applicant will submit within 3 months';
    case 'REQUEST_LEW':
      return 'LEW to prepare SLD';
    default:
      return value;
  }
}
