import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { StepTracker } from '../../components/domain/StepTracker';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import applicationApi from '../../api/applicationApi';
import priceApi from '../../api/priceApi';
import type { MasterPrice, PriceCalculation } from '../../types';

const STEPS = [
  { label: 'Address', description: 'Property details' },
  { label: 'kVA & Price', description: 'Select capacity' },
  { label: 'Review', description: 'Confirm & submit' },
];

const BUILDING_TYPES = [
  { value: '', label: 'Select building type' },
  { value: 'Residential', label: 'Residential' },
  { value: 'Commercial', label: 'Commercial' },
  { value: 'Industrial', label: 'Industrial' },
  { value: 'Hotel', label: 'Hotel' },
  { value: 'Healthcare', label: 'Healthcare' },
  { value: 'Education', label: 'Education' },
  { value: 'Government', label: 'Government' },
  { value: 'Mixed Use', label: 'Mixed Use' },
  { value: 'Other', label: 'Other' },
];

interface FormData {
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
}

export default function NewApplicationPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [currentStep, setCurrentStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [showSubmitConfirm, setShowSubmitConfirm] = useState(false);

  // Form data
  const [formData, setFormData] = useState<FormData>({
    address: '',
    postalCode: '',
    buildingType: '',
    selectedKva: null,
  });

  // Price data
  const [priceTiers, setPriceTiers] = useState<MasterPrice[]>([]);
  const [priceResult, setPriceResult] = useState<PriceCalculation | null>(null);
  const [loadingPrices, setLoadingPrices] = useState(false);

  // Load price tiers when entering step 2
  useEffect(() => {
    if (currentStep === 1 && priceTiers.length === 0) {
      setLoadingPrices(true);
      priceApi.getPrices()
        .then(setPriceTiers)
        .catch(() => toast.error('Failed to load price tiers'))
        .finally(() => setLoadingPrices(false));
    }
  }, [currentStep]);

  // Calculate price when kVA changes
  useEffect(() => {
    if (formData.selectedKva) {
      priceApi.calculatePrice(formData.selectedKva)
        .then(setPriceResult)
        .catch(() => setPriceResult(null));
    } else {
      setPriceResult(null);
    }
  }, [formData.selectedKva]);

  const updateField = <K extends keyof FormData>(field: K, value: FormData[K]) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    setErrors((prev) => ({ ...prev, [field]: '' }));
  };

  const validateStep1 = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!formData.address.trim()) newErrors.address = 'Address is required';
    if (!formData.postalCode.trim()) newErrors.postalCode = 'Postal code is required';
    if (formData.postalCode && !/^\d{6}$/.test(formData.postalCode.trim())) {
      newErrors.postalCode = 'Postal code must be 6 digits';
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const validateStep2 = (): boolean => {
    const newErrors: Record<string, string> = {};
    if (!formData.selectedKva) newErrors.selectedKva = 'Please select a kVA capacity';
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleNext = () => {
    if (currentStep === 0 && !validateStep1()) return;
    if (currentStep === 1 && !validateStep2()) return;
    setCurrentStep((prev) => Math.min(prev + 1, 2));
  };

  const handleBack = () => {
    setCurrentStep((prev) => Math.max(prev - 1, 0));
  };

  const handleSubmit = async () => {
    if (!formData.selectedKva) return;
    setShowSubmitConfirm(false);
    setSubmitting(true);
    try {
      const result = await applicationApi.createApplication({
        address: formData.address.trim(),
        postalCode: formData.postalCode.trim(),
        buildingType: formData.buildingType || undefined,
        selectedKva: formData.selectedKva,
      });
      toast.success('Application submitted successfully!');
      navigate(`/applications/${result.applicationSeq}`);
    } catch {
      toast.error('Failed to submit application. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/dashboard')}
          className="p-1.5 rounded-lg hover:bg-gray-100 text-gray-500 transition-colors"
        >
          <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">New Licence Application</h1>
          <p className="text-sm text-gray-500 mt-0.5">Apply for a new electrical installation licence</p>
        </div>
      </div>

      {/* Step tracker */}
      <Card>
        <StepTracker steps={STEPS} currentStep={currentStep} />
      </Card>

      {/* Step content */}
      <Card>
        {currentStep === 0 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-lg font-semibold text-gray-800">Property Details</h2>
              <p className="text-sm text-gray-500 mt-1">Enter the address of the electrical installation</p>
            </div>
            <Input
              label="Installation Address"
              placeholder="e.g., 123 Orchard Road, #10-01, Singapore"
              value={formData.address}
              onChange={(e) => updateField('address', e.target.value)}
              error={errors.address}
              required
            />
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <Input
                label="Postal Code"
                placeholder="e.g., 238888"
                value={formData.postalCode}
                onChange={(e) => updateField('postalCode', e.target.value)}
                error={errors.postalCode}
                required
              />
              <Select
                label="Building Type"
                value={formData.buildingType}
                onChange={(e) => updateField('buildingType', e.target.value)}
                options={BUILDING_TYPES}
              />
            </div>
          </div>
        )}

        {currentStep === 1 && (
          loadingPrices ? (
            <div className="flex items-center justify-center h-48">
              <LoadingSpinner size="lg" label="Loading price tiers..." />
            </div>
          ) : (
            <div className="space-y-5">
              <div>
                <h2 className="text-lg font-semibold text-gray-800">Capacity & Pricing</h2>
                <p className="text-sm text-gray-500 mt-1">Select the electrical capacity for your installation</p>
              </div>
              <Select
                label="DB Size (kVA)"
                value={formData.selectedKva ? String(formData.selectedKva) : ''}
                onChange={(e) => updateField('selectedKva', e.target.value ? Number(e.target.value) : null)}
                options={[
                  { value: '', label: 'Select kVA capacity' },
                  ...priceTiers.map((tier) => ({
                    value: String(tier.kvaMin),
                    label: `${tier.description} — SGD $${tier.price.toLocaleString()}`,
                  })),
                ]}
                error={errors.selectedKva}
                required
              />
              {priceResult && (
                <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm font-medium text-primary-700">Selected Tier</p>
                      <p className="text-lg font-semibold text-primary-800 mt-1">{priceResult.tierDescription}</p>
                    </div>
                    <div className="text-right">
                      <p className="text-sm font-medium text-primary-700">Estimated Price</p>
                      <p className="text-2xl font-bold text-primary-800 mt-1">
                        SGD ${priceResult.price.toLocaleString()}
                      </p>
                    </div>
                  </div>
                </div>
              )}
              {/* Price reference table */}
              <div>
                <h3 className="text-sm font-medium text-gray-600 mb-2">Price Reference Table</h3>
                <div className="border border-gray-200 rounded-lg overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="bg-gray-50">
                        <th className="text-left py-2 px-3 font-medium text-gray-600">Capacity</th>
                        <th className="text-right py-2 px-3 font-medium text-gray-600">Price (SGD)</th>
                      </tr>
                    </thead>
                    <tbody>
                      {priceTiers.map((tier) => (
                        <tr
                          key={tier.masterPriceSeq}
                          className={`border-t border-gray-100 ${
                            formData.selectedKva && formData.selectedKva >= tier.kvaMin && formData.selectedKva <= tier.kvaMax
                              ? 'bg-primary-50 font-medium'
                              : ''
                          }`}
                        >
                          <td className="py-2 px-3">{tier.description}</td>
                          <td className="py-2 px-3 text-right">${tier.price.toLocaleString()}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )
        )}

        {currentStep === 2 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-lg font-semibold text-gray-800">Review & Confirm</h2>
              <p className="text-sm text-gray-500 mt-1">Please review your application details before submitting</p>
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
            {/* Total */}
            <div className="bg-primary-50 rounded-xl p-5 border border-primary-100">
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-primary-700">Total Amount Due</span>
                <span className="text-2xl font-bold text-primary-800">
                  SGD ${priceResult?.price.toLocaleString() || '—'}
                </span>
              </div>
              <p className="text-xs text-primary-600 mt-2">
                Payment via PayNow or bank transfer. Details will be provided after submission.
              </p>
            </div>
          </div>
        )}

        {/* Navigation buttons */}
        <div className="flex justify-between mt-8 pt-6 border-t border-gray-100">
          <Button
            variant="outline"
            onClick={currentStep === 0 ? () => navigate('/dashboard') : handleBack}
          >
            {currentStep === 0 ? 'Cancel' : 'Back'}
          </Button>
          {currentStep < 2 ? (
            <Button onClick={handleNext}>Continue</Button>
          ) : (
            <Button onClick={() => setShowSubmitConfirm(true)} loading={submitting}>Submit Application</Button>
          )}
        </div>
      </Card>

      <ConfirmDialog
        isOpen={showSubmitConfirm}
        onClose={() => setShowSubmitConfirm(false)}
        onConfirm={handleSubmit}
        title="Submit Application"
        message="Submit this application? You will need to make payment after submission."
        confirmLabel="Submit"
      />
    </div>
  );
}
