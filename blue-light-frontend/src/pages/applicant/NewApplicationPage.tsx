import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card } from '../../components/ui/Card';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Select } from '../../components/ui/Select';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { InfoBox } from '../../components/ui/InfoBox';
import { ApplicantTypeCard } from '../../components/applicant/ApplicantTypeCard';
import { CompanyInfoModal } from '../../components/applicant/CompanyInfoModal';
import { StepTracker } from '../../components/domain/StepTracker';
import { LoadingSpinner } from '../../components/ui/LoadingSpinner';
import { useToastStore } from '../../stores/toastStore';
import { useFormGuard } from '../../hooks/useFormGuard';
import { useFormAutoSave } from '../../hooks/useFormAutoSave';
import { BeforeYouBeginGuide } from './steps/BeforeYouBeginGuide';
import { StepReview } from './steps/StepReview';
import applicationApi from '../../api/applicationApi';
import priceApi from '../../api/priceApi';
import { userApi } from '../../api/userApi';
import {
  validateApplicationStep0,
  validateApplicationStep1,
  validateApplicationStep2,
} from '../../utils/validation';
import type { MasterPrice, PriceCalculation, Application, ApplicantType, ApplicationType, CreateApplicationRequest, CompanyInfo } from '../../types';

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
  applicantType: ApplicantType;
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

  // Phase 2 PR#3 — 법인 JIT 모달 상태
  const [showCompanyModal, setShowCompanyModal] = useState(false);
  const [jitSubmitError, setJitSubmitError] = useState<string | null>(null);
  // User profile에 이미 회사정보가 있는지 여부 (Submit 시 한 번 조회)
  const [userHasCompanyInfo, setUserHasCompanyInfo] = useState<boolean | null>(null);

  // Phase 1 PR#3: 파일 업로드 UI/상태 전부 제거. LEW가 필요 시 이후 단계에서 요청함.

  // Form data
  const [formData, setFormData] = useState<FormData>({
    applicationType: 'NEW',
    applicantType: 'INDIVIDUAL', // AC-A3: 기본값 INDIVIDUAL
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

  // 폼 자동 저장 — sessionStorage 기반 (새로고침 시 복원)
  const { clear: clearDraft } = useFormAutoSave('new-application-draft', formData, setFormData, {
    debounceMs: 500,
    onRestore: () => {
      toast.info('Previous draft restored. Continue where you left off.');
      setShowGuide(false); // 가이드 스킵하고 폼으로 바로 이동
      return true;
    },
  });

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
        formData.sldOption,
        formData.applicationType
      )
        .then(setPriceResult)
        .catch(() => setPriceResult(null));
    } else {
      setPriceResult(null);
    }
  }, [formData.selectedKva, formData.renewalPeriodMonths, formData.sldOption, formData.applicationType]);

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

  // Validation per step (validation.ts 유틸리티 사용)
  const validateStep0 = (): boolean => {
    const newErrors = validateApplicationStep0(formData);
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const validateStep1 = (): boolean => {
    const newErrors = validateApplicationStep1(formData);
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const validateStep2 = (): boolean => {
    const newErrors = validateApplicationStep2(formData);
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

  /** 공통 payload 빌더 (JIT 모달 경로와 일반 경로 공유) */
  const buildPayload = (companyInfo?: CompanyInfo): CreateApplicationRequest | null => {
    if (!formData.selectedKva) return null;
    const payload: CreateApplicationRequest = {
      address: formData.address.trim(),
      postalCode: formData.postalCode.trim(),
      buildingType: formData.buildingType || undefined,
      selectedKva: formData.selectedKva,
      applicantType: formData.applicantType,
      applicationType: formData.applicationType,
      renewalPeriodMonths: formData.renewalPeriodMonths ?? undefined,
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
    if (companyInfo) {
      payload.companyInfo = companyInfo;
    }
    return payload;
  };

  /** 실제 API 호출 — 일반 경로와 JIT 경로 공유 */
  const createApplicationCall = async (payload: CreateApplicationRequest) => {
    const result = await applicationApi.createApplication(payload);
    clearDraft();
    toast.success('Application submitted successfully!');
    navigate(`/applications/${result.applicationSeq}`);
    return result;
  };

  /**
   * AC-J1 / AC-J5: Submit Confirm 에서 "Submit" 클릭 시 진입.
   * - INDIVIDUAL 이면 즉시 API 호출.
   * - CORPORATE + User에 회사정보가 이미 있으면 즉시 호출.
   * - CORPORATE + User 회사정보 없음이면 JIT 모달 열고 API는 아직 호출하지 않는다.
   */
  const handleSubmit = async () => {
    if (!formData.selectedKva) return;
    setShowSubmitConfirm(false);

    // 법인 preflight — 모달 표시 여부 결정
    if (formData.applicantType === 'CORPORATE') {
      // User.companyName 확인 — 캐시 없으면 1회 조회
      let hasCompany = userHasCompanyInfo;
      if (hasCompany === null) {
        try {
          const profile = await userApi.getMyProfile();
          hasCompany = !!(profile.companyName && profile.companyName.trim());
          setUserHasCompanyInfo(hasCompany);
        } catch {
          // 프로필 조회 실패 시 안전하게 모달 표시 (서버도 COMPANY_INFO_REQUIRED 방어)
          hasCompany = false;
          setUserHasCompanyInfo(false);
        }
      }

      if (!hasCompany) {
        setJitSubmitError(null);
        setShowCompanyModal(true);
        return; // 모달에서 confirm 시 제출
      }
    }

    setSubmitting(true);
    try {
      const payload = buildPayload();
      if (!payload) return;
      await createApplicationCall(payload);
    } catch {
      toast.error('Failed to submit application. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  /** AC-J2: JIT 모달 confirm — 단일 @Transactional 백엔드가 User 업데이트 + Application 생성 */
  const handleCompanyModalConfirm = async (info: CompanyInfo) => {
    setJitSubmitError(null);
    setSubmitting(true);
    try {
      const payload = buildPayload(info);
      if (!payload) {
        setSubmitting(false);
        return;
      }
      await createApplicationCall(payload);
      setShowCompanyModal(false);
      if (info.persistToProfile) {
        setUserHasCompanyInfo(true); // 다음 신청 시 모달 생략
      }
    } catch (err: unknown) {
      // 서버 400 INVALID_UEN / COMPANY_INFO_REQUIRED 등을 모달 내 표시
      const e = err as { response?: { data?: { code?: string; message?: string } }; message?: string };
      const code = e.response?.data?.code;
      let msg = e.response?.data?.message || 'Could not submit. Please try again.';
      if (code === 'INVALID_UEN') msg = 'Invalid UEN format. Check SG UEN rules.';
      if (code === 'COMPANY_INFO_REQUIRED') msg = 'Company info is required for corporate applications.';
      setJitSubmitError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  /** AC-J4: 모달 취소 — Step 3 폼 보존, API 호출 없음 */
  const handleCompanyModalCancel = () => {
    if (submitting) return;
    setShowCompanyModal(false);
    setJitSubmitError(null);
  };

  // Helper: reset renewal fields when switching type (applicantType은 사용자 선택 유지)
  const handleTypeChange = (type: ApplicationType) => {
    setFormData((prev) => ({
      applicationType: type,
      applicantType: prev.applicantType,
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
    }));
    setErrors({});
    setPriceResult(null);
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

            {/* Applicant Type — 개인/법인 카드형 라디오 (Phase 1 PR#3, AC-A3) */}
            <fieldset className="space-y-2 border-t border-gray-100 pt-5">
              <legend className="block text-sm font-medium text-gray-700 mb-2">
                Who is applying? <span className="text-red-500">*</span>
                <span className="ml-2 text-xs font-normal text-gray-500">
                  (default: Individual)
                </span>
              </legend>
              <div
                role="radiogroup"
                aria-label="Applicant Type"
                className="grid grid-cols-1 sm:grid-cols-2 gap-3"
              >
                <ApplicantTypeCard
                  value="INDIVIDUAL"
                  checked={formData.applicantType === 'INDIVIDUAL'}
                  onChange={(v) => updateField('applicantType', v)}
                />
                <ApplicantTypeCard
                  value="CORPORATE"
                  checked={formData.applicantType === 'CORPORATE'}
                  onChange={(v) => updateField('applicantType', v)}
                />
              </div>
              {formData.applicantType === 'CORPORATE' && (
                <p
                  className="text-xs text-gray-500 mt-3"
                  role="status"
                >
                  You may be asked for company details in a later step.
                </p>
              )}
              {errors.applicantType && (
                <p className="text-sm text-red-600">{errors.applicantType}</p>
              )}
            </fieldset>

            {/* SP Account Number — optional text only (no file upload) */}
            <div className="space-y-2 border-t border-gray-100 pt-5">
              <Input
                id="spAccountNo"
                label="SP Account Number (optional)"
                placeholder="e.g. 1234567890"
                value={formData.spAccountNo}
                onChange={(e) => updateField('spAccountNo', e.target.value)}
                hint="If you have it on hand. Otherwise your LEW will ask later."
              />
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

            {/* Phase 1 PR#3: "서류 제출 필요 없음" 안내 (AC-A1, 하단 고정) */}
            <div className="border-t border-gray-100 pt-5">
              <InfoBox title="No documents needed now">
                Your assigned Licensed Electrical Worker (LEW) will review your
                application and request any required documents — SP account,
                LOA, main breaker photo, SLD — through the platform. This keeps
                your first step fast.
              </InfoBox>
            </div>
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
          <StepReview formData={formData} priceResult={priceResult} />
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

      {/* Phase 2 PR#3: 법인 JIT 회사정보 모달 */}
      <CompanyInfoModal
        isOpen={showCompanyModal}
        submitting={submitting}
        submitError={jitSubmitError}
        onConfirm={handleCompanyModalConfirm}
        onCancel={handleCompanyModalCancel}
      />

      {/* Phase 1 PR#3: SP Account Email Sample Modal, SamplePreviewModal 제거 (파일 업로드 UI 제거와 함께) */}
    </div>
  );
}
