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
import type { MasterPrice, PriceCalculation, Application, ApplicationType } from '../../types';

const STEPS = [
  { label: 'Type', description: 'Application type' },
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
  applicationType: ApplicationType;
  spAccountNo: string;
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
  // Renewal fields
  originalApplicationSeq: number | null;
  existingLicenceNo: string;
  existingExpiryDate: string;
  renewalPeriodMonths: number | null;
  renewalReferenceNo: string;
  manualEntry: boolean;
  // SLD option
  sldOption: 'SELF_UPLOAD' | 'REQUEST_LEW';
}

export default function NewApplicationPage() {
  const navigate = useNavigate();
  const toast = useToastStore();
  const [showGuide, setShowGuide] = useState(true);
  const [currentStep, setCurrentStep] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [showSubmitConfirm, setShowSubmitConfirm] = useState(false);

  // Form data
  const [formData, setFormData] = useState<FormData>({
    applicationType: 'NEW',
    spAccountNo: '',
    address: '',
    postalCode: '',
    buildingType: '',
    selectedKva: null,
    originalApplicationSeq: null,
    existingLicenceNo: '',
    existingExpiryDate: '',
    renewalPeriodMonths: null,
    renewalReferenceNo: '',
    manualEntry: false,
    sldOption: 'SELF_UPLOAD',
  });

  // Price data
  const [priceTiers, setPriceTiers] = useState<MasterPrice[]>([]);
  const [priceResult, setPriceResult] = useState<PriceCalculation | null>(null);
  const [loadingPrices, setLoadingPrices] = useState(false);

  // Completed applications for renewal
  const [completedApps, setCompletedApps] = useState<Application[]>([]);
  const [loadingCompleted, setLoadingCompleted] = useState(false);

  // Load completed applications when selecting RENEWAL
  useEffect(() => {
    if (formData.applicationType === 'RENEWAL' && completedApps.length === 0) {
      setLoadingCompleted(true);
      applicationApi.getCompletedApplications()
        .then((apps) => {
          setCompletedApps(apps);
          // If no completed apps, auto-enable manual entry
          if (apps.length === 0) {
            setFormData((prev) => ({ ...prev, manualEntry: true }));
          }
        })
        .catch(() => toast.error('Failed to load completed applications'))
        .finally(() => setLoadingCompleted(false));
    }
  }, [formData.applicationType]);

  // Load price tiers when entering kVA step
  useEffect(() => {
    if (currentStep === 2 && priceTiers.length === 0) {
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

  // Handle selecting an existing completed application
  const handleSelectOriginalApp = (appSeq: number | null) => {
    if (!appSeq) {
      updateField('originalApplicationSeq', null);
      return;
    }
    const app = completedApps.find((a) => a.applicationSeq === appSeq);
    if (app) {
      setFormData((prev) => ({
        ...prev,
        originalApplicationSeq: app.applicationSeq,
        existingLicenceNo: app.licenseNumber || '',
        existingExpiryDate: app.licenseExpiryDate || '',
        address: app.address,
        postalCode: app.postalCode,
        buildingType: app.buildingType || '',
        selectedKva: app.selectedKva,
      }));
      setErrors({});
    }
  };

  // Validation per step
  const validateStep0 = (): boolean => {
    const newErrors: Record<string, string> = {};
    // Licence period is required for both NEW and RENEWAL
    if (!formData.renewalPeriodMonths) {
      newErrors.renewalPeriodMonths = 'Please select a licence period';
    }
    if (formData.applicationType === 'RENEWAL') {
      if (formData.manualEntry) {
        if (!formData.existingLicenceNo.trim()) {
          newErrors.existingLicenceNo = 'Existing licence number is required';
        }
        if (!formData.existingExpiryDate.trim()) {
          newErrors.existingExpiryDate = 'Existing expiry date is required';
        }
      } else if (!formData.originalApplicationSeq) {
        newErrors.originalApplicationSeq = 'Please select an existing application';
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
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
    if (currentStep === 0 && !validateStep0()) return;
    if (currentStep === 1 && !validateStep1()) return;
    if (currentStep === 2 && !validateStep2()) return;
    setCurrentStep((prev) => Math.min(prev + 1, 3));
  };

  const handleBack = () => {
    setCurrentStep((prev) => Math.max(prev - 1, 0));
  };

  const handleSubmit = async () => {
    if (!formData.selectedKva) return;
    setShowSubmitConfirm(false);
    setSubmitting(true);
    try {
      const payload: Record<string, unknown> = {
        address: formData.address.trim(),
        postalCode: formData.postalCode.trim(),
        buildingType: formData.buildingType || undefined,
        selectedKva: formData.selectedKva,
        applicationType: formData.applicationType,
        renewalPeriodMonths: formData.renewalPeriodMonths,
        spAccountNo: formData.spAccountNo.trim() || undefined,
        sldOption: formData.sldOption,
      };
      if (formData.applicationType === 'RENEWAL') {
        if (formData.renewalReferenceNo.trim()) {
          payload.renewalReferenceNo = formData.renewalReferenceNo.trim();
        }
        if (formData.originalApplicationSeq && !formData.manualEntry) {
          payload.originalApplicationSeq = formData.originalApplicationSeq;
        } else {
          payload.existingLicenceNo = formData.existingLicenceNo.trim();
          payload.existingExpiryDate = formData.existingExpiryDate;
        }
      }
      const result = await applicationApi.createApplication(payload as any);
      toast.success('Application submitted successfully!');
      navigate(`/applications/${result.applicationSeq}`);
    } catch {
      toast.error('Failed to submit application. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  // Helper: reset renewal fields when switching type
  const handleTypeChange = (type: ApplicationType) => {
    setFormData({
      applicationType: type,
      spAccountNo: '',
      address: '',
      postalCode: '',
      buildingType: '',
      selectedKva: null,
      originalApplicationSeq: null,
      existingLicenceNo: '',
      existingExpiryDate: '',
      renewalPeriodMonths: null,
      renewalReferenceNo: '',
      manualEntry: false,
      sldOption: 'SELF_UPLOAD',
    });
    setErrors({});
    setPriceResult(null);
  };

  // Compute EMA fee label (Supply Installation: 12mo=$150, others: 12mo=$100; 3mo always $50)
  const getEmaFeeLabel = (months: number | null) => {
    if (months === 3) return 'SGD $50';
    if (months === 12) {
      return formData.applicationType === 'SUPPLY_INSTALLATION' ? 'SGD $150' : 'SGD $100';
    }
    return '—';
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
          <p className="text-sm text-gray-500 mt-0.5">Apply for a new or renewal electrical installation licence</p>
        </div>
      </div>

      {/* ───── Before You Begin Guide ───── */}
      {showGuide && (
        <Card>
          <div className="space-y-6">
            <div>
              <h2 className="text-lg font-semibold text-gray-800">Before You Begin</h2>
              <p className="text-sm text-gray-500 mt-1">
                Please review the following checklist to ensure a smooth application process.
              </p>
            </div>

            {/* Process Overview */}
            <div className="bg-gray-50 rounded-xl p-5 border border-gray-200">
              <h3 className="text-sm font-semibold text-gray-700 uppercase tracking-wider mb-4">Application Process</h3>
              <div className="space-y-3">
                {[
                  { step: '1', title: 'Submit Application', desc: 'Fill in property details, select kVA capacity, and review pricing.' },
                  { step: '2', title: 'Upload Documents', desc: 'Upload required documents including SLD (Single Line Diagram) and authorisation letter.' },
                  { step: '3', title: 'LEW Review', desc: 'A Licensed Electrical Worker will review your application. You may be asked to revise.' },
                  { step: '4', title: 'Make Payment', desc: 'Once approved, complete payment via PayNow or bank transfer.' },
                  { step: '5', title: 'Licence Issued', desc: 'After verification, your electrical installation licence will be issued.' },
                ].map(({ step, title, desc }) => (
                  <div key={step} className="flex items-start gap-3">
                    <div className="flex-shrink-0 w-7 h-7 bg-primary-100 text-primary-700 rounded-full flex items-center justify-center text-sm font-bold">
                      {step}
                    </div>
                    <div>
                      <p className="text-sm font-medium text-gray-800">{title}</p>
                      <p className="text-xs text-gray-500 mt-0.5">{desc}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Required Documents Checklist */}
            <div className="bg-amber-50 rounded-xl p-5 border border-amber-200">
              <h3 className="text-sm font-semibold text-amber-800 uppercase tracking-wider mb-3">Required Documents</h3>
              <p className="text-xs text-amber-700 mb-3">Prepare these documents before starting your application. You can upload them after submission.</p>
              <ul className="space-y-2">
                {[
                  { label: 'Single Line Diagram (SLD)', desc: 'Accepted formats: PDF, JPG, DWG, DXF, DGN, TIF, GIF, ZIP' },
                  { label: "Owner's Authorisation Letter", desc: 'Signed letter authorising the electrical installation work' },
                ].map(({ label, desc }) => (
                  <li key={label} className="flex items-start gap-2.5">
                    <svg className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
                    </svg>
                    <div>
                      <p className="text-sm font-medium text-amber-900">{label}</p>
                      <p className="text-xs text-amber-700">{desc}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </div>

            {/* Key Information */}
            <div className="bg-blue-50 rounded-xl p-5 border border-blue-200">
              <h3 className="text-sm font-semibold text-blue-800 uppercase tracking-wider mb-3">Key Information</h3>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div className="flex items-start gap-2">
                  <span className="text-blue-600 mt-0.5">💰</span>
                  <div>
                    <p className="text-sm font-medium text-blue-900">Pricing</p>
                    <p className="text-xs text-blue-700">Based on your DB Size (kVA). Service fee and EMA fee apply.</p>
                  </div>
                </div>
                <div className="flex items-start gap-2">
                  <span className="text-blue-600 mt-0.5">⏱️</span>
                  <div>
                    <p className="text-sm font-medium text-blue-900">Licence Period</p>
                    <p className="text-xs text-blue-700">Choose between 3-month or 12-month licence validity.</p>
                  </div>
                </div>
                <div className="flex items-start gap-2">
                  <span className="text-blue-600 mt-0.5">🔌</span>
                  <div>
                    <p className="text-sm font-medium text-blue-900">SP Group Account</p>
                    <p className="text-xs text-blue-700">You need an SP Group utilities account before applying.</p>
                  </div>
                </div>
                <div className="flex items-start gap-2">
                  <span className="text-blue-600 mt-0.5">📋</span>
                  <div>
                    <p className="text-sm font-medium text-blue-900">EMA Submission</p>
                    <p className="text-xs text-blue-700">Files for ELISE submission must be under 2MB each.</p>
                  </div>
                </div>
              </div>
            </div>

            {/* Start Application Button */}
            <div className="flex justify-between items-center pt-2">
              <Button variant="outline" onClick={() => navigate('/dashboard')}>
                Cancel
              </Button>
              <Button onClick={() => setShowGuide(false)}>
                Start Application
              </Button>
            </div>
          </div>
        </Card>
      )}

      {/* Step tracker */}
      {!showGuide && (
        <Card>
          <StepTracker steps={STEPS} currentStep={currentStep} />
        </Card>
      )}

      {/* Step content */}
      {!showGuide && <Card>
        {/* ───── Step 0: Application Type ───── */}
        {currentStep === 0 && (
          <div className="space-y-6">
            {/* SP Group Account Notice */}
            <div className="bg-blue-50 border border-blue-200 rounded-xl p-4">
              <div className="flex items-start gap-3">
                <div className="flex-shrink-0 w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                  <svg className="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                  </svg>
                </div>
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-blue-800">SP Group Account Required</h3>
                  <p className="text-sm text-blue-700 mt-1">
                    Before applying for an electrical installation licence, you need an SP Group utilities account.
                    If you don't have one yet, please open an account first.
                  </p>
                  <a
                    href="https://openaccount.spgroup.com.sg"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 mt-2 text-sm font-medium text-blue-700 hover:text-blue-900 underline underline-offset-2"
                  >
                    Open SP Group Account
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                    </svg>
                  </a>
                </div>
              </div>
              <div className="mt-4 pt-3 border-t border-blue-200">
                <Input
                  label="SP Account Number"
                  placeholder="e.g., 1234567890"
                  value={formData.spAccountNo}
                  onChange={(e) => updateField('spAccountNo', e.target.value)}
                  hint="Optional. Enter your SP Group account number if available."
                />
              </div>
            </div>

            <div>
              <h2 className="text-lg font-semibold text-gray-800">Application Type</h2>
              <p className="text-sm text-gray-500 mt-1">Choose the type of licence application</p>
            </div>

            {/* Type selection cards */}
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
              {([
                { type: 'NEW' as ApplicationType, icon: '🏢', title: 'New Licence', desc: 'Apply for a brand new electrical installation licence' },
                { type: 'RENEWAL' as ApplicationType, icon: '🔄', title: 'Licence Renewal', desc: 'Renew an existing electrical installation licence' },
                { type: 'SUPPLY_INSTALLATION' as ApplicationType, icon: '⚡', title: 'Supply Installation', desc: 'Apply for a temporary electricity supply licence' },
              ]).map(({ type, icon, title, desc }) => (
                <button
                  key={type}
                  type="button"
                  onClick={() => handleTypeChange(type)}
                  className={`relative p-5 rounded-xl border-2 text-left transition-all ${
                    formData.applicationType === type
                      ? 'border-primary-500 bg-primary-50 ring-1 ring-primary-200'
                      : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <span className="text-2xl">{icon}</span>
                    <div>
                      <p className="font-semibold text-gray-800">{title}</p>
                      <p className="text-sm text-gray-500 mt-1">{desc}</p>
                    </div>
                  </div>
                  {formData.applicationType === type && (
                    <div className="absolute top-3 right-3">
                      <svg className="w-5 h-5 text-primary-600" fill="currentColor" viewBox="0 0 20 20">
                        <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                      </svg>
                    </div>
                  )}
                </button>
              ))}
            </div>

            {/* Licence Period Selection (applicable to both NEW and RENEWAL) */}
            <div className="space-y-2 border-t border-gray-100 pt-5">
              <label className="block text-sm font-medium text-gray-700">
                Licence Period <span className="text-red-500">*</span>
              </label>
              <p className="text-xs text-gray-500 mb-2">
                Select the duration for your electrical installation licence
              </p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <button
                  type="button"
                  onClick={() => updateField('renewalPeriodMonths', 12)}
                  className={`p-4 rounded-lg border-2 text-left transition-all ${
                    formData.renewalPeriodMonths === 12
                      ? 'border-primary-500 bg-primary-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <p className="font-semibold text-gray-800">12 Months</p>
                  <p className="text-sm text-gray-500 mt-0.5">
                    EMA Fee: {formData.applicationType === 'SUPPLY_INSTALLATION' ? 'SGD $150' : 'SGD $100'}
                  </p>
                </button>
                <button
                  type="button"
                  onClick={() => updateField('renewalPeriodMonths', 3)}
                  className={`p-4 rounded-lg border-2 text-left transition-all ${
                    formData.renewalPeriodMonths === 3
                      ? 'border-primary-500 bg-primary-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <p className="font-semibold text-gray-800">3 Months</p>
                  <p className="text-sm text-gray-500 mt-0.5">EMA Fee: SGD $50</p>
                </button>
              </div>
              {errors.renewalPeriodMonths && (
                <p className="text-sm text-red-600">{errors.renewalPeriodMonths}</p>
              )}
            </div>

            {/* SLD Option Selection */}
            <div className="space-y-2 border-t border-gray-100 pt-5">
              <label className="block text-sm font-medium text-gray-700">
                Single Line Diagram (SLD) <span className="text-red-500">*</span>
              </label>
              <p className="text-xs text-gray-500 mb-2">
                An SLD is required for your application. Choose how you'd like to provide it.
              </p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <button
                  type="button"
                  onClick={() => updateField('sldOption', 'SELF_UPLOAD')}
                  className={`p-4 rounded-lg border-2 text-left transition-all ${
                    formData.sldOption === 'SELF_UPLOAD'
                      ? 'border-primary-500 bg-primary-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <span className="text-xl flex-shrink-0 mt-0.5">📄</span>
                    <div>
                      <p className="font-semibold text-gray-800">Upload Myself</p>
                      <p className="text-sm text-gray-500 mt-0.5">
                        I have an SLD ready and will upload it after submission
                      </p>
                    </div>
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => updateField('sldOption', 'REQUEST_LEW')}
                  className={`p-4 rounded-lg border-2 text-left transition-all ${
                    formData.sldOption === 'REQUEST_LEW'
                      ? 'border-emerald-500 bg-emerald-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <span className="text-xl flex-shrink-0 mt-0.5">🔧</span>
                    <div>
                      <p className="font-semibold text-gray-800">Request LEW to Prepare</p>
                      <p className="text-sm text-gray-500 mt-0.5">
                        A Licensed Electrical Worker will prepare the SLD for you
                      </p>
                      <p className="text-xs text-emerald-600 font-medium mt-1">
                        Additional fee may apply (to be determined)
                      </p>
                    </div>
                  </div>
                </button>
              </div>
            </div>

            {/* Renewal-specific fields */}
            {formData.applicationType === 'RENEWAL' && (
              <div className="space-y-5 border-t border-gray-100 pt-5">
                {/* Select existing completed application */}
                {loadingCompleted ? (
                  <div className="flex items-center justify-center py-8">
                    <LoadingSpinner size="md" label="Loading completed applications..." />
                  </div>
                ) : (
                  <>
                    {completedApps.length > 0 && !formData.manualEntry && (
                      <div className="space-y-3">
                        <label className="block text-sm font-medium text-gray-700">
                          Select Existing Licence <span className="text-red-500">*</span>
                        </label>
                        <div className="space-y-2">
                          {completedApps.map((app) => (
                            <button
                              key={app.applicationSeq}
                              type="button"
                              onClick={() => handleSelectOriginalApp(app.applicationSeq)}
                              className={`w-full text-left p-3 rounded-lg border transition-all ${
                                formData.originalApplicationSeq === app.applicationSeq
                                  ? 'border-primary-500 bg-primary-50'
                                  : 'border-gray-200 hover:border-gray-300 hover:bg-gray-50'
                              }`}
                            >
                              <div className="flex items-center justify-between">
                                <div>
                                  <p className="text-sm font-medium text-gray-800">
                                    {app.licenseNumber || `Application #${app.applicationSeq}`}
                                  </p>
                                  <p className="text-xs text-gray-500 mt-0.5">
                                    {app.address} • {app.selectedKva} kVA
                                    {app.licenseExpiryDate && ` • Expires: ${app.licenseExpiryDate}`}
                                  </p>
                                </div>
                                {formData.originalApplicationSeq === app.applicationSeq && (
                                  <svg className="w-5 h-5 text-primary-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                                  </svg>
                                )}
                              </div>
                            </button>
                          ))}
                        </div>
                        {errors.originalApplicationSeq && (
                          <p className="text-sm text-red-600">{errors.originalApplicationSeq}</p>
                        )}
                        <button
                          type="button"
                          onClick={() => {
                            setFormData((prev) => ({
                              ...prev,
                              manualEntry: true,
                              originalApplicationSeq: null,
                              existingLicenceNo: '',
                              existingExpiryDate: '',
                              address: '',
                              postalCode: '',
                              buildingType: '',
                              selectedKva: null,
                            }));
                            setPriceResult(null);
                          }}
                          className="text-sm text-primary-600 hover:text-primary-700 font-medium"
                        >
                          Or enter details manually →
                        </button>
                      </div>
                    )}

                    {/* Manual entry mode */}
                    {(formData.manualEntry || completedApps.length === 0) && (
                      <div className="space-y-4">
                        {completedApps.length > 0 && (
                          <button
                            type="button"
                            onClick={() => {
                              setFormData((prev) => ({
                                ...prev,
                                manualEntry: false,
                                originalApplicationSeq: null,
                                existingLicenceNo: '',
                                existingExpiryDate: '',
                                address: '',
                                postalCode: '',
                                buildingType: '',
                                selectedKva: null,
                              }));
                              setPriceResult(null);
                            }}
                            className="text-sm text-primary-600 hover:text-primary-700 font-medium"
                          >
                            ← Select from existing applications
                          </button>
                        )}
                        <Input
                          label="Existing Licence Number"
                          placeholder="e.g., EI-2025-001"
                          value={formData.existingLicenceNo}
                          onChange={(e) => updateField('existingLicenceNo', e.target.value)}
                          error={errors.existingLicenceNo}
                          required
                        />
                        <Input
                          label="Existing Licence Expiry Date"
                          type="date"
                          value={formData.existingExpiryDate}
                          onChange={(e) => updateField('existingExpiryDate', e.target.value)}
                          error={errors.existingExpiryDate}
                          required
                        />
                      </div>
                    )}
                  </>
                )}

                {/* Renewal Reference No (optional) */}
                <Input
                  label="Renewal Reference No."
                  placeholder="e.g., RN-2025-001 (optional)"
                  value={formData.renewalReferenceNo}
                  onChange={(e) => updateField('renewalReferenceNo', e.target.value)}
                />
              </div>
            )}
          </div>
        )}

        {/* ───── Step 1: Address ───── */}
        {currentStep === 1 && (
          <div className="space-y-5">
            <div>
              <h2 className="text-lg font-semibold text-gray-800">Property Details</h2>
              <p className="text-sm text-gray-500 mt-1">
                {formData.applicationType === 'RENEWAL' && formData.originalApplicationSeq
                  ? 'Auto-filled from your previous application. You may modify if needed.'
                  : 'Enter the address of the electrical installation'}
              </p>
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

        {/* ───── Step 2: kVA & Price ───── */}
        {currentStep === 2 && (
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

              {/* Price breakdown */}
              {priceResult && (
                <div className="bg-primary-50 rounded-xl p-5 border border-primary-100 space-y-3">
                  <div className="flex items-center justify-between">
                    <div>
                      <p className="text-sm font-medium text-primary-700">Selected Tier</p>
                      <p className="text-lg font-semibold text-primary-800 mt-1">{priceResult.tierDescription}</p>
                    </div>
                  </div>
                  <div className="border-t border-primary-200 pt-3 space-y-2">
                    <div className="flex justify-between text-sm">
                      <span className="text-primary-700">kVA Tier Price</span>
                      <span className="font-medium text-primary-800">SGD ${priceResult.price.toLocaleString()}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-primary-700">Service Fee</span>
                      <span className="font-medium text-primary-800">SGD ${priceResult.serviceFee.toLocaleString()}</span>
                    </div>
                    <div className="border-t border-primary-200 pt-2 flex justify-between">
                      <span className="text-sm font-semibold text-primary-700">Total Amount</span>
                      <span className="text-xl font-bold text-primary-800">
                        SGD ${priceResult.totalAmount.toLocaleString()}
                      </span>
                    </div>
                  </div>
                  {/* EMA Fee info */}
                  {formData.renewalPeriodMonths && (
                    <div className="bg-amber-50 rounded-lg p-3 border border-amber-200 mt-2">
                      <div className="flex items-start gap-2">
                        <span className="text-amber-600 mt-0.5">ℹ️</span>
                        <div>
                          <p className="text-sm font-medium text-amber-800">
                            EMA Fee: {getEmaFeeLabel(formData.renewalPeriodMonths)} ({formData.renewalPeriodMonths}-month licence)
                          </p>
                          <p className="text-xs text-amber-600 mt-0.5">
                            Paid directly to EMA (Energy Market Authority). Not included in the total above.
                          </p>
                        </div>
                      </div>
                    </div>
                  )}
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

        {/* ───── Step 3: Review ───── */}
        {currentStep === 3 && (
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
        )}

        {/* Navigation buttons */}
        <div className="flex justify-between mt-8 pt-6 border-t border-gray-100">
          <Button
            variant="outline"
            onClick={currentStep === 0 ? () => setShowGuide(true) : handleBack}
          >
            {currentStep === 0 ? 'Back to Guide' : 'Back'}
          </Button>
          {currentStep < 3 ? (
            <Button onClick={handleNext}>Continue</Button>
          ) : (
            <Button onClick={() => setShowSubmitConfirm(true)} loading={submitting}>Submit Application</Button>
          )}
        </div>
      </Card>}

      <ConfirmDialog
        isOpen={showSubmitConfirm}
        onClose={() => setShowSubmitConfirm(false)}
        onConfirm={handleSubmit}
        title="Submit Application"
        message={`Submit this ${
          formData.applicationType === 'RENEWAL' ? 'renewal'
            : formData.applicationType === 'SUPPLY_INSTALLATION' ? 'supply installation'
            : ''
        } application? You will need to make payment after submission.`}
        confirmLabel="Submit"
      />
    </div>
  );
}
