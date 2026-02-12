import { Card } from '../../../components/ui/Card';
import { Button } from '../../../components/ui/Button';
import { Input } from '../../../components/ui/Input';
import { Select } from '../../../components/ui/Select';
import { InfoField } from '../../../components/common/InfoField';
import type { Application, MasterPrice } from '../../../types';

interface EditState {
  address: string;
  postalCode: string;
  buildingType: string;
  kva: number;
  price: number | null;
}

interface ApplicationInfoProps {
  application: Application;
  editMode: boolean;
  editState: EditState;
  prices: MasterPrice[];
  submitting: boolean;
  onEditStateChange: (field: keyof EditState, value: string | number) => void;
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
            <Input
              label="Installation Address"
              required
              maxLength={255}
              value={editState.address}
              onChange={(e) => onEditStateChange('address', e.target.value)}
            />
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Postal Code"
                required
                maxLength={10}
                value={editState.postalCode}
                onChange={(e) => onEditStateChange('postalCode', e.target.value)}
              />
              <Input
                label="Building Type"
                maxLength={50}
                value={editState.buildingType}
                onChange={(e) => onEditStateChange('buildingType', e.target.value)}
              />
            </div>
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
                disabled={!editState.address || !editState.postalCode || !editState.kva}
              >
                Resubmit Application
              </Button>
              <Button variant="outline" onClick={onCancelEdit}>
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <InfoField label="Installation Address" value={application.address} />
            <InfoField label="Postal Code" value={application.postalCode} />
            <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
            <InfoField label="Electric Box (kVA)" value={`${application.selectedKva} kVA`} />
            {application.spAccountNo && (
              <InfoField label="SP Account No." value={application.spAccountNo} />
            )}
          </div>
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
              value={application.emaFee ? `SGD $${application.emaFee.toLocaleString()} (Paid to EMA)` : '—'}
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
