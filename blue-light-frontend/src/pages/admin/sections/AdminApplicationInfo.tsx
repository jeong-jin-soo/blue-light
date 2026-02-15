import { Card } from '../../../components/ui/Card';
import { InfoField } from '../../../components/common/InfoField';
import type { AdminApplication } from '../../../types';

interface Props {
  application: AdminApplication;
  onNavigateToOriginal?: (seq: number) => void;
}

/**
 * 신청 정보 섹션
 * - 신청자 정보, 물건 정보, 면허 기간, 갱신 정보, 가격 정보
 */
export function AdminApplicationInfo({ application, onNavigateToOriginal }: Props) {
  return (
    <>
      {/* Applicant Info */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Applicant Information</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <InfoField label="Name" value={application.userName} />
          <InfoField label="Email" value={application.userEmail} />
          <InfoField label="Phone" value={application.userPhone || 'Not provided'} />
          <InfoField label="Designation" value={application.userDesignation || '—'} />
        </div>

        {/* Business Details */}
        <div className="mt-4 pt-4 border-t border-gray-100">
          <h3 className="text-sm font-medium text-gray-600 mb-3">
            Business Details
            <span className="text-xs text-gray-400 ml-1.5">(Required for Letter of Appointment)</span>
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <InfoField label="Company Name" value={application.userCompanyName || '—'} />
            <InfoField label="UEN" value={application.userUen || '—'} />
          </div>
        </div>

        {/* Correspondence Address */}
        <div className="mt-4 pt-4 border-t border-gray-100">
          <h3 className="text-sm font-medium text-gray-600 mb-3">
            Correspondence Address
            <span className="text-xs text-gray-400 ml-1.5">(EMA notification delivery)</span>
          </h3>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <InfoField label="Address" value={application.userCorrespondenceAddress || '—'} />
            <InfoField label="Postal Code" value={application.userCorrespondencePostalCode || '—'} />
          </div>
        </div>

        {/* Missing info warning */}
        {(!application.userCompanyName || !application.userUen || !application.userDesignation || !application.userCorrespondenceAddress) && (
          <div className="mt-4 bg-warning-50 border border-warning-200 rounded-lg p-3">
            <div className="flex items-start gap-2">
              <span className="text-sm">⚠️</span>
              <div>
                <p className="text-xs font-medium text-warning-800">Incomplete Applicant Profile</p>
                <p className="text-xs text-warning-700 mt-0.5">
                  The following are required for Letter of Appointment:{' '}
                  {[
                    !application.userCompanyName && 'Company Name',
                    !application.userUen && 'UEN',
                    !application.userDesignation && 'Designation',
                    !application.userCorrespondenceAddress && 'Correspondence Address',
                  ].filter(Boolean).join(', ')}.
                  Please ask the applicant to update their profile.
                </p>
              </div>
            </div>
          </div>
        )}
      </Card>

      {/* Property Details */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Property Details</h2>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <InfoField label="Installation Address" value={application.address} />
          <InfoField label="Postal Code" value={application.postalCode} />
          <InfoField label="Building Type" value={application.buildingType || 'Not specified'} />
          <InfoField label="Electric Box (kVA)" value={`${application.selectedKva} kVA`} />
          {application.spAccountNo && (
            <InfoField label="SP Account No." value={application.spAccountNo} />
          )}
        </div>
      </Card>

      {/* Licence Period */}
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

      {/* Renewal Details */}
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
                <div>
                  <dt className="text-xs text-gray-500">Original Application</dt>
                  <dd className="text-sm font-medium text-primary-600 mt-0.5">
                    <button
                      onClick={() => onNavigateToOriginal?.(application.originalApplicationSeq!)}
                      className="hover:underline"
                    >
                      #{application.originalApplicationSeq} →
                    </button>
                  </dd>
                </div>
              )}
            </div>
          </div>
        </Card>
      )}

      {/* Pricing */}
      <Card>
        <h2 className="text-lg font-semibold text-gray-800 mb-4">Pricing</h2>
        <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
          {application.serviceFee != null && (
            <div className="space-y-2 mb-3">
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">kVA Tier Price</span>
                <span className="font-medium text-primary-800">
                  SGD ${(application.quoteAmount - (application.serviceFee || 0) - (application.emaFee || 0)).toLocaleString()}
                </span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">Service Fee</span>
                <span className="font-medium text-primary-800">
                  SGD ${application.serviceFee.toLocaleString()}
                </span>
              </div>
              {application.emaFee != null && application.emaFee > 0 && (
                <div className="flex justify-between text-sm">
                  <span className="text-primary-700">EMA Fee ({application.renewalPeriodMonths}-month)</span>
                  <span className="font-medium text-primary-800">
                    SGD ${application.emaFee.toLocaleString()}
                  </span>
                </div>
              )}
              <div className="border-t border-primary-200 pt-2"></div>
            </div>
          )}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-primary-700">Total Amount</p>
              <p className="text-xs text-primary-600 mt-1">
                Based on {application.selectedKva} kVA capacity
              </p>
            </div>
            <p className="text-2xl font-bold text-primary-800">
              SGD ${application.quoteAmount.toLocaleString()}
            </p>
          </div>
        </div>
      </Card>
    </>
  );
}
