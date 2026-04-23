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

  const KvaLine = () => (
    <div>
      <dt className="text-xs text-gray-500 mb-0.5">Electric Box (kVA)</dt>
      {application.kvaStatus === 'UNKNOWN' ? (
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
        <KvaLine />
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
        <KvaLine />
        {application.spAccountNo && (
          <InfoField label="SP Account No." value={application.spAccountNo} />
        )}
      </div>
    </div>
  );
}
