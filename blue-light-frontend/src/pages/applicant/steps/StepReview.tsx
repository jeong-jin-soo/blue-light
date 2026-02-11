import type { PriceCalculation, ApplicationType } from '../../../types';

interface FormData {
  applicationType: ApplicationType;
  spAccountNo: string;
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
  existingLicenceNo: string;
  existingExpiryDate: string;
  renewalPeriodMonths: number | null;
  renewalReferenceNo: string;
  sldOption: 'SELF_UPLOAD' | 'REQUEST_LEW';
}

interface StepReviewProps {
  formData: FormData;
  priceResult: PriceCalculation | null;
  getEmaFeeLabel: (months: number | null) => string;
}

export function StepReview({ formData, priceResult, getEmaFeeLabel }: StepReviewProps) {
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
            : formData.applicationType === 'SUPPLY_INSTALLATION'
              ? 'bg-yellow-100 text-yellow-800'
              : 'bg-blue-100 text-blue-800'
        }`}>
          {formData.applicationType === 'RENEWAL' ? '🔄 Licence Renewal'
            : formData.applicationType === 'SUPPLY_INSTALLATION' ? '⚡ Supply Installation'
            : '🏢 New Licence'}
        </span>
      </div>

      {/* SP Account Number (if provided) */}
      {formData.spAccountNo.trim() && (
        <div className="bg-blue-50 rounded-lg p-4 space-y-2 border border-blue-100">
          <h3 className="text-sm font-semibold text-blue-700 uppercase tracking-wider">SP Group Account</h3>
          <div>
            <dt className="text-xs text-blue-600">Account Number</dt>
            <dd className="text-sm font-medium text-blue-800 mt-0.5">{formData.spAccountNo}</dd>
          </div>
        </div>
      )}

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
                {getEmaFeeLabel(formData.renewalPeriodMonths)}
                <span className="text-xs text-gray-500 ml-1">(Paid to EMA)</span>
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
            {formData.renewalReferenceNo && (
              <div className="sm:col-span-2">
                <dt className="text-xs text-orange-600">Renewal Reference No.</dt>
                <dd className="text-sm font-medium text-orange-800 mt-0.5">
                  {formData.renewalReferenceNo}
                </dd>
              </div>
            )}
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
              : 'You will upload the SLD yourself'}
          </span>
        </div>
        {formData.sldOption === 'REQUEST_LEW' && (
          <p className="text-xs text-emerald-600">
            An SLD drawing request will be automatically sent to the assigned LEW after submission.
            Additional fee may apply.
          </p>
        )}
      </div>

      {/* Property Details */}
      <div className="bg-gray-50 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Property Details</h3>
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
      </div>

      {/* Capacity & Price */}
      <div className="bg-gray-50 rounded-lg p-4 space-y-3">
        <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider">Capacity & Pricing</h3>
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
      </div>

      {/* Total with breakdown */}
      <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
        {priceResult && (
          <div className="space-y-2 mb-3">
            <div className="flex justify-between text-sm">
              <span className="text-primary-700">kVA Tier Price</span>
              <span className="font-medium text-primary-800">SGD ${priceResult.price.toLocaleString()}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-primary-700">Service Fee</span>
              <span className="font-medium text-primary-800">SGD ${priceResult.serviceFee.toLocaleString()}</span>
            </div>
            <div className="border-t border-primary-200 pt-2"></div>
          </div>
        )}
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-primary-700">Total Amount Due</span>
          <span className="text-2xl font-bold text-primary-800">
            SGD ${priceResult?.totalAmount.toLocaleString() || '—'}
          </span>
        </div>
        <p className="text-xs text-primary-600 mt-2">
          Payment via PayNow or bank transfer. Details will be provided after submission.
        </p>
        {formData.renewalPeriodMonths && (
          <p className="text-xs text-amber-600 mt-1">
            * EMA fee of {getEmaFeeLabel(formData.renewalPeriodMonths)} ({formData.renewalPeriodMonths}-month licence) is payable directly to EMA and is not included in the above total.
          </p>
        )}
      </div>
    </div>
  );
}
