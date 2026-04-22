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

  // Correspondence 평문 4-part (block/unit/street/building)
  const correspondenceLines = [
    data.correspondenceAddressBlockPlain,
    data.correspondenceAddressUnitPlain,
    data.correspondenceAddressStreetPlain,
    data.correspondenceAddressBuildingPlain,
    app.correspondenceAddressPostalCode,
  ].filter((v): v is string => !!v && v.trim().length > 0);

  const installationLines = [
    app.installationAddressBlock,
    app.installationAddressUnit,
    app.installationAddressStreet,
    app.installationAddressBuilding,
    app.installationAddressPostalCode,
  ].filter((v): v is string => !!v && v.trim().length > 0);

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
            <SummaryField
              label="Address"
              value={installationLines.length > 0 ? installationLines.join(', ') : app.address}
              full
            />
            <SummaryField label="Postal Code" value={app.postalCode} />
            <SummaryField label="Building Type" value={app.buildingType} />
            <SummaryField
              label="Rental Premises"
              value={app.isRentalPremises ? 'Yes' : 'No'}
            />
          </dl>
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
            <SummaryField
              label="Correspondence Address"
              value={
                correspondenceLines.length > 0
                  ? correspondenceLines.join(', ')
                  : 'Same as installation address'
              }
              full
            />
            {data.landlordEiLicenceNo && (
              <SummaryField
                label="Landlord EI Licence (plain, LEW-only)"
                value={data.landlordEiLicenceNo}
                emphasis
                full
              />
            )}
          </dl>
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
