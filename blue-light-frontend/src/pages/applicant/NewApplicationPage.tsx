import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { Modal, ModalHeader, ModalBody, ModalFooter } from '../../components/ui/Modal';
import { StepTracker } from '../../components/domain/StepTracker';
import { SpAccountEmailSample } from '../../components/domain/SpAccountEmailSample';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { useFormGuard } from '../../hooks/useFormGuard';
import { BeforeYouBeginGuide } from './steps/BeforeYouBeginGuide';
import { StepReview } from './steps/StepReview';
import applicationApi from '../../api/applicationApi';
import priceApi from '../../api/priceApi';
import fileApi from '../../api/fileApi';
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

  // SLD file (held client-side until application is created)
  const [sldFile, setSldFile] = useState<File | null>(null);
  // LOA email screenshot (held client-side until application is created)
  const [loaEmailFile, setLoaEmailFile] = useState<File | null>(null);
  // Main breaker box photo (held client-side until application is created)
  const [breakerBoxPhoto, setBreakerBoxPhoto] = useState<File | null>(null);
  // SP Account document (held client-side until application is created)
  const [spAccountFile, setSpAccountFile] = useState<File | null>(null);
  // SP Account sample modal
  const [showSpSample, setShowSpSample] = useState(false);

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

  // Form leave guard — warn when navigating away with unsaved data
  const isFormDirty = useMemo(() => {
    if (showGuide || submitting) return false;
    return !!(formData.address || formData.postalCode || formData.spAccountNo || formData.selectedKva);
  }, [showGuide, submitting, formData.address, formData.postalCode, formData.spAccountNo, formData.selectedKva]);
  useFormGuard(isFormDirty);

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

  // Calculate price when kVA, licence period, or SLD option changes
  useEffect(() => {
    if (formData.selectedKva) {
      priceApi.calculatePrice(
        formData.selectedKva,
        formData.renewalPeriodMonths || undefined,
        formData.sldOption
      )
        .then(setPriceResult)
        .catch(() => setPriceResult(null));
    } else {
      setPriceResult(null);
    }
  }, [formData.selectedKva, formData.renewalPeriodMonths, formData.sldOption]);

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
      if (!formData.renewalReferenceNo.trim()) {
        newErrors.renewalReferenceNo = 'Renewal reference number is required';
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
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const handleBack = () => {
    setCurrentStep((prev) => Math.max(prev - 1, 0));
    window.scrollTo({ top: 0, behavior: 'smooth' });
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

      // Upload SLD file if attached
      if (sldFile && formData.sldOption === 'SELF_UPLOAD') {
        try {
          await fileApi.uploadFile(result.applicationSeq, sldFile, 'DRAWING_SLD');
        } catch {
          // Application created successfully, but SLD upload failed — user can retry from detail page
          toast.warning('Application submitted, but SLD upload failed. You can upload it from the application detail page.');
          navigate(`/applications/${result.applicationSeq}`);
          return;
        }
      }

      // Upload LOA document if attached (Renewal only)
      if (loaEmailFile && formData.applicationType === 'RENEWAL') {
        try {
          await fileApi.uploadFile(result.applicationSeq, loaEmailFile, 'OWNER_AUTH_LETTER');
        } catch {
          toast.warning('Application submitted, but LOA upload failed. You can upload it from the application detail page.');
          navigate(`/applications/${result.applicationSeq}`);
          return;
        }
      }

      // Upload main breaker box photo if attached
      if (breakerBoxPhoto) {
        try {
          await fileApi.uploadFile(result.applicationSeq, breakerBoxPhoto, 'SITE_PHOTO');
        } catch {
          toast.warning('Application submitted, but breaker box photo upload failed. You can upload it from the application detail page.');
          navigate(`/applications/${result.applicationSeq}`);
          return;
        }
      }

      // Upload SP Account document if attached (NEW only)
      if (spAccountFile && formData.applicationType === 'NEW') {
        try {
          await fileApi.uploadFile(result.applicationSeq, spAccountFile, 'SP_ACCOUNT_DOC');
        } catch {
          toast.warning('Application submitted, but SP account document upload failed. You can upload it from the application detail page.');
          navigate(`/applications/${result.applicationSeq}`);
          return;
        }
      }

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
    setSldFile(null);
    setLoaEmailFile(null);
    setSpAccountFile(null);
  };

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/dashboard')}
          className="flex items-center gap-1 px-2 py-1.5 rounded-lg hover:bg-gray-100 text-gray-500 text-sm transition-colors"
          aria-label="Back to dashboard"
        >
          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
          <span>Back</span>
        </button>
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-gray-800">New Licence Application</h1>
          <p className="text-sm text-gray-500 mt-0.5">Apply for a new or renewal electrical installation licence</p>
        </div>
      </div>

      {/* ───── Before You Begin Guide ───── */}
      {showGuide && (
        <BeforeYouBeginGuide
          onStart={() => { setShowGuide(false); window.scrollTo({ top: 0, behavior: 'smooth' }); }}
          onCancel={() => navigate('/dashboard')}
        />
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
            <div>
              <h2 className="text-lg font-semibold text-gray-800">Application Type</h2>
              <p className="text-sm text-gray-500 mt-1">Choose the type of licence application</p>
            </div>

            {/* Type selection cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {([
                { type: 'NEW' as ApplicationType, icon: '🏢', title: 'New Licence', desc: 'Apply for a brand new electrical installation licence. An SP Group account is required.' },
                { type: 'RENEWAL' as ApplicationType, icon: '🔄', title: 'Licence Renewal', desc: 'Renew an existing electrical installation licence' },
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

            {/* SP Account Notice + Input — NEW only */}
            {formData.applicationType === 'NEW' && (
              <div className="bg-blue-50 border border-blue-200 rounded-xl p-5 space-y-4">
                <div className="flex items-start gap-3">
                  <span className="text-xl mt-0.5">💡</span>
                  <div>
                    <p className="text-sm font-semibold text-blue-800">SP Group Account Required</p>
                    <p className="text-sm text-blue-700 mt-1">
                      New licence applications require an active SP Group electricity account. If you don't have one yet, please apply at{' '}
                      <a
                        href="https://www.spgroup.com.sg"
                        target="_blank"
                        rel="noopener noreferrer"
                        className="underline font-medium hover:text-blue-900"
                      >
                        SP Group website
                      </a>{' '}
                      before proceeding.
                    </p>
                  </div>
                </div>
                <div>
                  <label htmlFor="spAccountNo" className="block text-sm font-medium text-blue-800 mb-1">
                    SP Account Number
                  </label>
                  <input
                    id="spAccountNo"
                    type="text"
                    placeholder="e.g. 1234567890"
                    value={formData.spAccountNo}
                    onChange={(e) => updateField('spAccountNo', e.target.value)}
                    className="w-full px-3 py-2 border border-blue-200 rounded-lg text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-400 focus:border-transparent placeholder-gray-400"
                  />
                  <p className="text-xs text-blue-600 mt-1">
                    Enter your SP Group account number if available. You can also provide it later.
                  </p>
                </div>

                {/* SP Account Email Screenshot/PDF Upload */}
                <div>
                  <label className="block text-sm font-medium text-blue-800 mb-1">
                    SP Account Email Screenshot / PDF <span className="text-red-500">*</span>
                  </label>
                  <p className="text-xs text-blue-600 mb-2">
                    Upload a screenshot or PDF of your SP Group account confirmation email. Accepted formats: PDF, JPG, JPEG. You can also upload it later.
                  </p>
                  <button
                    type="button"
                    onClick={() => setShowSpSample(true)}
                    className="inline-flex items-center gap-1.5 text-xs font-medium text-blue-700 hover:text-blue-900 mb-2 transition-colors"
                  >
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                    </svg>
                    View sample email
                  </button>
                  <div className="bg-white/60 rounded-lg p-3 border border-blue-200">
                    {spAccountFile ? (
                      <div className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-blue-200">
                        <div className="flex items-center gap-2 min-w-0">
                          <span className="text-lg">📧</span>
                          <div className="min-w-0">
                            <p className="text-sm font-medium text-gray-700 truncate">{spAccountFile.name}</p>
                            <p className="text-xs text-gray-400">
                              {spAccountFile.size < 1024 * 1024
                                ? `${(spAccountFile.size / 1024).toFixed(1)} KB`
                                : `${(spAccountFile.size / (1024 * 1024)).toFixed(1)} MB`}
                            </p>
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => setSpAccountFile(null)}
                          className="text-gray-400 hover:text-red-500 transition-colors p-1"
                          aria-label="Remove SP account file"
                        >
                          <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                          </svg>
                        </button>
                      </div>
                    ) : (
                      <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-blue-300 rounded-lg cursor-pointer hover:border-blue-400 hover:bg-blue-50/30 transition-colors">
                        <svg className="w-5 h-5 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                        </svg>
                        <span className="text-sm text-blue-600">Choose file</span>
                        <input
                          type="file"
                          accept=".pdf,.jpg,.jpeg"
                          className="hidden"
                          onChange={(e) => {
                            const f = e.target.files?.[0];
                            if (f) {
                              if (f.size > 10 * 1024 * 1024) {
                                toast.error('File size must be under 10 MB');
                                return;
                              }
                              setSpAccountFile(f);
                            }
                            e.target.value = '';
                          }}
                        />
                      </label>
                    )}
                  </div>
                </div>
              </div>
            )}

            {/* LOA Upload — Renewal only */}
            {formData.applicationType === 'RENEWAL' && (
            <div className="space-y-2 border-t border-gray-100 pt-5">
              <label className="block text-sm font-medium text-gray-700">
                📄 Letter of Appointment (LOA) Document
              </label>
              <p className="text-xs text-gray-500 mb-2">
                Upload the LOA document received from the relevant authority. You can also upload it later.
              </p>
              <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                {loaEmailFile ? (
                  <div className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-gray-200">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-lg">🖼️</span>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-700 truncate">{loaEmailFile.name}</p>
                        <p className="text-xs text-gray-400">
                          {loaEmailFile.size < 1024 * 1024
                            ? `${(loaEmailFile.size / 1024).toFixed(1)} KB`
                            : `${(loaEmailFile.size / (1024 * 1024)).toFixed(1)} MB`}
                        </p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setLoaEmailFile(null)}
                      className="text-gray-400 hover:text-red-500 transition-colors p-1"
                      aria-label="Remove LOA file"
                    >
                      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                ) : (
                  <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
                    <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                    </svg>
                    <span className="text-sm text-gray-600">Choose LOA document</span>
                    <input
                      type="file"
                      accept=".pdf,.jpg,.jpeg"
                      className="hidden"
                      onChange={(e) => {
                        const f = e.target.files?.[0];
                        if (f) {
                          if (f.size > 10 * 1024 * 1024) {
                            toast.error('File size must be under 10 MB');
                            return;
                          }
                          setLoaEmailFile(f);
                        }
                        e.target.value = '';
                      }}
                    />
                  </label>
                )}
              </div>
            </div>
            )}

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
                    EMA Fee: SGD $100
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

            {/* Main Breaker Box Photo */}
            <div className="space-y-2 border-t border-gray-100 pt-5">
              <label className="block text-sm font-medium text-gray-700">
                Main Breaker Box Photo
              </label>
              <p className="text-xs text-gray-500 mb-2">
                Upload a photo of the main breaker box at the installation site. This helps verify the electrical capacity (kVA). You can also upload it later.
              </p>
              <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                {breakerBoxPhoto ? (
                  <div className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-gray-200">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-lg">📷</span>
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-700 truncate">{breakerBoxPhoto.name}</p>
                        <p className="text-xs text-gray-400">
                          {breakerBoxPhoto.size < 1024 * 1024
                            ? `${(breakerBoxPhoto.size / 1024).toFixed(1)} KB`
                            : `${(breakerBoxPhoto.size / (1024 * 1024)).toFixed(1)} MB`}
                        </p>
                      </div>
                    </div>
                    <button
                      type="button"
                      onClick={() => setBreakerBoxPhoto(null)}
                      className="text-gray-400 hover:text-red-500 transition-colors p-1"
                      aria-label="Remove breaker box photo"
                    >
                      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                      </svg>
                    </button>
                  </div>
                ) : (
                  <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
                    <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
                    </svg>
                    <span className="text-sm text-gray-600">Choose photo file</span>
                    <input
                      type="file"
                      accept=".jpg,.jpeg,.png,.heic,.heif"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) {
                          if (file.size > 10 * 1024 * 1024) {
                            toast.error('File size must be less than 10MB');
                            return;
                          }
                          setBreakerBoxPhoto(file);
                        }
                        e.target.value = '';
                      }}
                    />
                  </label>
                )}
              </div>
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
                        I have an SLD ready and will attach it now or upload later
                      </p>
                    </div>
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => { updateField('sldOption', 'REQUEST_LEW'); setSldFile(null); }}
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

              {/* SLD File Attachment (shown when SELF_UPLOAD is selected) */}
              {formData.sldOption === 'SELF_UPLOAD' && (
                <div className="bg-gray-50 rounded-lg p-4 border border-gray-200">
                  <div className="flex items-start gap-2 mb-3">
                    <span className="text-sm">📎</span>
                    <div>
                      <p className="text-sm font-medium text-gray-700">Attach SLD File (Optional)</p>
                      <p className="text-xs text-gray-500 mt-0.5">
                        You can attach your SLD now, or upload it later from the application detail page.
                      </p>
                    </div>
                  </div>

                  {sldFile ? (
                    <div className="flex items-center justify-between px-3 py-2.5 bg-white rounded-lg border border-gray-200">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-lg">📄</span>
                        <div className="min-w-0">
                          <p className="text-sm font-medium text-gray-700 truncate">{sldFile.name}</p>
                          <p className="text-xs text-gray-400">
                            {sldFile.size < 1024 * 1024
                              ? `${(sldFile.size / 1024).toFixed(1)} KB`
                              : `${(sldFile.size / (1024 * 1024)).toFixed(1)} MB`}
                          </p>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => setSldFile(null)}
                        className="text-gray-400 hover:text-red-500 transition-colors p-1"
                        aria-label="Remove SLD file"
                      >
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                        </svg>
                      </button>
                    </div>
                  ) : (
                    <label className="flex items-center justify-center gap-2 px-4 py-3 border-2 border-dashed border-gray-300 rounded-lg cursor-pointer hover:border-primary-400 hover:bg-primary-50/30 transition-colors">
                      <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                      </svg>
                      <span className="text-sm text-gray-600">Choose SLD file</span>
                      <input
                        type="file"
                        accept=".pdf,.jpg,.jpeg,.png,.dwg,.dxf,.dgn,.tif,.tiff,.gif,.zip"
                        className="hidden"
                        onChange={(e) => {
                          const file = e.target.files?.[0];
                          if (file) {
                            if (file.size > 10 * 1024 * 1024) {
                              toast.error('File size must be less than 10MB');
                              return;
                            }
                            setSldFile(file);
                          }
                          e.target.value = '';
                        }}
                      />
                    </label>
                  )}
                </div>
              )}
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

                {/* Renewal Reference No (required) */}
                <Input
                  label="Renewal Reference No."
                  placeholder="e.g., RN-2025-001"
                  value={formData.renewalReferenceNo}
                  onChange={(e) => updateField('renewalReferenceNo', e.target.value)}
                  error={errors.renewalReferenceNo}
                  required
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
                label="Electric Box (kVA)"
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
                    {priceResult.sldFee != null && priceResult.sldFee > 0 && (
                      <div className="flex justify-between text-sm">
                        <span className="text-primary-700">SLD Drawing Fee</span>
                        <span className="font-medium text-primary-800">SGD ${priceResult.sldFee.toLocaleString()}</span>
                      </div>
                    )}
                    {priceResult.emaFee != null && priceResult.emaFee > 0 && (
                      <div className="flex justify-between text-sm">
                        <span className="text-primary-700">EMA Fee ({formData.renewalPeriodMonths}-month)</span>
                        <span className="font-medium text-primary-800">SGD ${priceResult.emaFee.toLocaleString()}</span>
                      </div>
                    )}
                    <div className="border-t border-primary-200 pt-2 flex justify-between">
                      <span className="text-sm font-semibold text-primary-700">Total Amount</span>
                      <span className="text-xl font-bold text-primary-800">
                        SGD ${priceResult.totalAmount.toLocaleString()}
                      </span>
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

        {/* ───── Step 3: Review ───── */}
        {currentStep === 3 && (
          <StepReview formData={formData} priceResult={priceResult} sldFile={sldFile} loaEmailFile={loaEmailFile} breakerBoxPhoto={breakerBoxPhoto} spAccountFile={spAccountFile} />
        )}

        {/* Navigation buttons */}
        <div className="flex justify-between mt-8 pt-6 border-t border-gray-100">
          <Button
            variant="outline"
            onClick={currentStep === 0 ? () => { setShowGuide(true); window.scrollTo({ top: 0, behavior: 'smooth' }); } : handleBack}
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
          formData.applicationType === 'RENEWAL' ? 'renewal ' : ''
        }application? You will need to make payment after submission.`}
        confirmLabel="Submit"
      />

      {/* SP Account Email Sample Modal */}
      <Modal isOpen={showSpSample} onClose={() => setShowSpSample(false)} size="lg">
        <ModalHeader title="SP Account Email — Sample" onClose={() => setShowSpSample(false)} />
        <ModalBody>
          <p className="text-sm text-gray-600 mb-4">
            Below are examples of the SP Services Ltd account confirmation emails. Please upload a screenshot or PDF of a similar email you received from SP Group.
          </p>
          <SpAccountEmailSample />
        </ModalBody>
        <ModalFooter>
          <Button variant="outline" onClick={() => setShowSpSample(false)}>Close</Button>
        </ModalFooter>
      </Modal>
    </div>
  );
}
