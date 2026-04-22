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
import { KvaTipBox } from '../../components/applicant/KvaTipBox';
import { KvaPriceCard } from '../../components/applicant/KvaPriceCard';
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
import { BUILDING_TYPES, KVA_UNKNOWN_SENTINEL } from '../../constants/orderFormOptions';

const STEPS = [
  { label: 'Type', description: 'Application type' },
  { label: 'Address', description: 'Property details' },
  { label: 'kVA & Price', description: 'Select capacity' },
  { label: 'Review', description: 'Confirm & submit' },
];

interface FormData {
  applicationType: ApplicationType;
  applicantType: ApplicantType;
  spAccountNo: string;
  address: string;
  postalCode: string;
  buildingType: string;
  selectedKva: number | null;
  // Phase 5: kVA UNKNOWN 플래그 (I don't know 선택 시 true)
  kvaUnknown: boolean;
  // Renewal fields
  originalApplicationSeq: number | null;
  existingLicenceNo: string;
  existingExpiryDate: string;
  renewalPeriodMonths: number | null;
  renewalReferenceNo: string;
  manualEntry: boolean;
  // SLD option (P1.2: 3-way로 확장)
  sldOption: 'SELF_UPLOAD' | 'SUBMIT_WITHIN_3_MONTHS' | 'REQUEST_LEW';
  // ── P1 Step 1: EMA 확장 플래그 ──
  isRentalPremises: boolean;            // NEW + 임대 체크
  renewalCompanyNameChanged: boolean;   // RENEWAL 시 변경 체크박스 2개
  renewalAddressChanged: boolean;
  // ── P1 Step 2: Installation Name ──
  useCustomInstallationName: boolean;   // 기본 false → "이름 자동 생성"
  installationName: string;             // useCustomInstallationName=true일 때만 편집 가능
  // ── P1 Step 4: Declaration 3-group + Correspondence + Landlord JIT ──
  declarationGroup1Accepted: boolean;   // "정보 사실·허위기재 법적 책임" (pre-check 허용)
  declarationGroup2Accepted: boolean;   // "전기 설비가 SG 전기 규정/SP 기술요건 부합"
  declarationGroup3Accepted: boolean;   // "LEW의 정기 점검·EMA 보고 동의"
  correspondenceSameAsInstallation: boolean;  // 기본 true
  correspondenceBlock: string;
  correspondenceUnit: string;
  correspondenceStreet: string;
  correspondenceBuilding: string;
  correspondencePostalCode: string;
  landlordEiLicenceNo: string;          // NEW + 임대 시 Step 4에서 수집
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
    kvaUnknown: false,
    originalApplicationSeq: null,
    existingLicenceNo: '',
    existingExpiryDate: '',
    renewalPeriodMonths: null,
    renewalReferenceNo: '',
    manualEntry: false,
    sldOption: 'SELF_UPLOAD',
    isRentalPremises: false,
    renewalCompanyNameChanged: false,
    renewalAddressChanged: false,
    useCustomInstallationName: false,
    installationName: '',
    declarationGroup1Accepted: true,   // 이미 상식에 속하는 사실 서약은 기본 체크 (UX 스펙 §5)
    declarationGroup2Accepted: false,
    declarationGroup3Accepted: false,
    correspondenceSameAsInstallation: true,
    correspondenceBlock: '',
    correspondenceUnit: '',
    correspondenceStreet: '',
    correspondenceBuilding: '',
    correspondencePostalCode: '',
    landlordEiLicenceNo: '',
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
    return !!(formData.address || formData.postalCode || formData.spAccountNo || formData.selectedKva || formData.kvaUnknown);
  }, [showGuide, submitting, formData.address, formData.postalCode, formData.spAccountNo, formData.selectedKva, formData.kvaUnknown]);
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
  // Phase 5: kvaUnknown 시에는 price breakdown을 숨기므로 계산 스킵
  useEffect(() => {
    if (formData.kvaUnknown) {
      setPriceResult(null);
      return;
    }
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
  }, [formData.selectedKva, formData.kvaUnknown, formData.renewalPeriodMonths, formData.sldOption, formData.applicationType]);

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
    // Phase 5: kvaUnknown 시 selectedKva는 placeholder 45 — 서버가 강제 덮어쓰지만 미리 세팅
    if (!formData.kvaUnknown && !formData.selectedKva) return null;
    const payload: CreateApplicationRequest = {
      address: formData.address.trim(),
      postalCode: formData.postalCode.trim(),
      buildingType: formData.buildingType || undefined,
      selectedKva: formData.kvaUnknown ? 45 : (formData.selectedKva as number),
      applicantType: formData.applicantType,
      applicationType: formData.applicationType,
      renewalPeriodMonths: formData.renewalPeriodMonths ?? undefined,
      spAccountNo: formData.spAccountNo.trim() || undefined,
      sldOption: formData.sldOption,
      kvaUnknown: formData.kvaUnknown || undefined,
      // ── P1.3: EMA ELISE 확장 필드 (Step 1에서 수집된 플래그) ──
      isRentalPremises: formData.isRentalPremises || undefined,
      renewalCompanyNameChanged: formData.applicationType === 'RENEWAL'
        ? formData.renewalCompanyNameChanged
        : undefined,
      renewalAddressChanged: formData.applicationType === 'RENEWAL'
        ? formData.renewalAddressChanged
        : undefined,
      // ── P1.4: Step 2·4에서 수집된 EMA 필드 ──
      installationName: formData.useCustomInstallationName && formData.installationName.trim()
        ? formData.installationName.trim()
        : undefined,
      landlordEiLicenceNo: formData.isRentalPremises && formData.landlordEiLicenceNo.trim()
        ? formData.landlordEiLicenceNo.trim()
        : undefined,
      correspondenceAddressBlock: !formData.correspondenceSameAsInstallation
        ? formData.correspondenceBlock.trim() || undefined
        : undefined,
      correspondenceAddressUnit: !formData.correspondenceSameAsInstallation
        ? formData.correspondenceUnit.trim() || undefined
        : undefined,
      correspondenceAddressStreet: !formData.correspondenceSameAsInstallation
        ? formData.correspondenceStreet.trim() || undefined
        : undefined,
      correspondenceAddressBuilding: !formData.correspondenceSameAsInstallation
        ? formData.correspondenceBuilding.trim() || undefined
        : undefined,
      correspondenceAddressPostalCode: !formData.correspondenceSameAsInstallation
        ? formData.correspondencePostalCode.trim() || undefined
        : undefined,
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
    // Phase 5: kvaUnknown 상태면 selectedKva는 미정 → payload에서 45로 placeholder
    if (!formData.kvaUnknown && !formData.selectedKva) return;
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
      kvaUnknown: false,
      originalApplicationSeq: null,
      existingLicenceNo: '',
      existingExpiryDate: '',
      renewalPeriodMonths: null,
      renewalReferenceNo: '',
      manualEntry: false,
      sldOption: 'SELF_UPLOAD',
      isRentalPremises: false,
      renewalCompanyNameChanged: false,
      renewalAddressChanged: false,
      useCustomInstallationName: false,
      installationName: '',
      declarationGroup1Accepted: true,
      declarationGroup2Accepted: false,
      declarationGroup3Accepted: false,
      correspondenceSameAsInstallation: true,
      correspondenceBlock: '',
      correspondenceUnit: '',
      correspondenceStreet: '',
      correspondenceBuilding: '',
      correspondencePostalCode: '',
      landlordEiLicenceNo: '',
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
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
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
                        I have an SLD ready and will attach it now
                      </p>
                    </div>
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => updateField('sldOption', 'SUBMIT_WITHIN_3_MONTHS')}
                  className={`p-4 rounded-lg border-2 text-left transition-all ${
                    formData.sldOption === 'SUBMIT_WITHIN_3_MONTHS'
                      ? 'border-primary-500 bg-primary-50'
                      : 'border-gray-200 hover:border-gray-300'
                  }`}
                >
                  <div className="flex items-start gap-3">
                    <span className="text-xl flex-shrink-0 mt-0.5">⏳</span>
                    <div>
                      <p className="font-semibold text-gray-800">Submit Within 3 Months</p>
                      <p className="text-sm text-gray-500 mt-0.5">
                        EMA allows submission within 3 months of application
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
                        Additional fee may apply
                      </p>
                    </div>
                  </div>
                </button>
              </div>
            </div>

            {/* ── P1.3: NEW 경로 전용 — 임대 시설 체크 (Landlord EI Licence는 Step 4에서 JIT로 수집) ── */}
            {formData.applicationType === 'NEW' && (
              <div className="border-t border-gray-100 pt-5">
                <label className="inline-flex items-start gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={formData.isRentalPremises}
                    onChange={(e) => updateField('isRentalPremises', e.target.checked)}
                    className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                  />
                  <span className="text-sm text-gray-700">
                    This is a rental premises
                    <span className="block text-xs text-gray-500 mt-0.5">
                      We'll ask for the landlord's EI licence number before you submit.
                    </span>
                  </span>
                </label>
              </div>
            )}

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

                {/* ── P1.3: 이전 신청 대비 변경 사항 체크박스 2개 ── */}
                <div className="border-t border-gray-100 pt-5 space-y-2">
                  <p className="text-sm font-medium text-gray-700">
                    Changes since your last application
                  </p>
                  <label className="flex items-start gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.renewalCompanyNameChanged}
                      onChange={(e) => updateField('renewalCompanyNameChanged', e.target.checked)}
                      className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                    />
                    <span className="text-sm text-gray-700">
                      Company name has changed
                      <span className="block text-xs text-gray-500 mt-0.5">
                        We'll ask you to re-confirm company info before submit.
                      </span>
                    </span>
                  </label>
                  <label className="flex items-start gap-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={formData.renewalAddressChanged}
                      onChange={(e) => updateField('renewalAddressChanged', e.target.checked)}
                      className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                    />
                    <span className="text-sm text-gray-700">
                      Installation address has changed
                      <span className="block text-xs text-gray-500 mt-0.5">
                        The address step will require your re-entry.
                      </span>
                    </span>
                  </label>
                </div>
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

            {/* ── P1.4: Installation Name — 기본 자동 생성, "다르게 지정" 토글 시 편집 ── */}
            {formData.applicationType === 'NEW' && (
              <div className="space-y-2 border-t border-gray-100 pt-4">
                <label className="inline-flex items-start gap-2 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={formData.useCustomInstallationName}
                    onChange={(e) => updateField('useCustomInstallationName', e.target.checked)}
                    className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                  />
                  <span className="text-sm text-gray-700">
                    Use a custom installation name
                    <span className="block text-xs text-gray-500 mt-0.5">
                      Default: your applicant name + "Premises". Toggle to override.
                    </span>
                  </span>
                </label>
                {formData.useCustomInstallationName && (
                  <Input
                    label="Installation Name"
                    placeholder="e.g., ABC Factory Block A"
                    value={formData.installationName}
                    onChange={(e) => updateField('installationName', e.target.value)}
                    maxLength={200}
                  />
                )}
              </div>
            )}
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

              {/* Phase 5 — Not sure about kVA? 안내 (pre-select 하지 않음) */}
              <KvaTipBox buildingType={formData.buildingType} />

              <Select
                label="Electric Box (kVA)"
                value={
                  formData.kvaUnknown
                    ? KVA_UNKNOWN_SENTINEL
                    : (formData.selectedKva ? String(formData.selectedKva) : '')
                }
                onChange={(e) => {
                  const v = e.target.value;
                  if (v === KVA_UNKNOWN_SENTINEL) {
                    // I don't know 선택 — selectedKva는 서버가 45로 강제
                    setFormData((prev) => ({ ...prev, kvaUnknown: true, selectedKva: null }));
                    setErrors((prev) => ({ ...prev, selectedKva: '' }));
                  } else {
                    setFormData((prev) => ({
                      ...prev,
                      kvaUnknown: false,
                      selectedKva: v ? Number(v) : null,
                    }));
                    setErrors((prev) => ({ ...prev, selectedKva: '' }));
                  }
                }}
                options={[
                  { value: '', label: 'Select kVA capacity' },
                  { value: KVA_UNKNOWN_SENTINEL, label: "I don't know — let LEW confirm me later" },
                  { value: '__DIVIDER__', label: '────────────────────', disabled: true },
                  ...priceTiers.map((tier) => ({
                    value: String(tier.kvaMin),
                    label: `${tier.description} — SGD $${tier.price.toLocaleString()}`,
                  })),
                ]}
                error={errors.selectedKva}
                required
              />

              {/* Phase 5 — 가격 카드 (UNKNOWN 시 "From S$350" + deactivated table) */}
              <KvaPriceCard
                kvaUnknown={formData.kvaUnknown}
                selectedKva={formData.selectedKva}
                priceResult={priceResult}
                priceTiers={priceTiers}
                renewalPeriodMonths={formData.renewalPeriodMonths}
              />
            </div>
          )
        )}

        {/* ───── Step 3: Review ───── */}
        {currentStep === 3 && (
          <div className="space-y-5">
            <StepReview formData={formData} priceResult={priceResult} />

            {/* ── P1.4: Correspondence Address — 기본은 Installation과 동일, 해제 시 5-part 노출 ── */}
            <div className="bg-gray-50 rounded-lg p-4 space-y-3 border border-gray-100">
              <label className="inline-flex items-start gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.correspondenceSameAsInstallation}
                  onChange={(e) => updateField('correspondenceSameAsInstallation', e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-sm text-gray-700">
                  Correspondence address is the same as installation address
                  <span className="block text-xs text-gray-500 mt-0.5">
                    Uncheck to enter a different postal address.
                  </span>
                </span>
              </label>
              {!formData.correspondenceSameAsInstallation && (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-2">
                  <Input
                    label="Block / House No"
                    value={formData.correspondenceBlock}
                    onChange={(e) => updateField('correspondenceBlock', e.target.value)}
                    maxLength={20}
                  />
                  <Input
                    label="Unit #"
                    value={formData.correspondenceUnit}
                    onChange={(e) => updateField('correspondenceUnit', e.target.value)}
                    maxLength={20}
                  />
                  <Input
                    label="Street Name"
                    className="sm:col-span-2"
                    value={formData.correspondenceStreet}
                    onChange={(e) => updateField('correspondenceStreet', e.target.value)}
                    maxLength={200}
                  />
                  <Input
                    label="Building"
                    value={formData.correspondenceBuilding}
                    onChange={(e) => updateField('correspondenceBuilding', e.target.value)}
                    maxLength={200}
                  />
                  <Input
                    label="Postal Code"
                    value={formData.correspondencePostalCode}
                    onChange={(e) => updateField('correspondencePostalCode', e.target.value)}
                    maxLength={10}
                  />
                </div>
              )}
            </div>

            {/* ── P1.4: Landlord EI Licence — NEW + 임대 체크 시에만 노출 ── */}
            {formData.applicationType === 'NEW' && formData.isRentalPremises && (
              <div className="bg-amber-50 border border-amber-200 rounded-lg p-4 space-y-2">
                <h3 className="text-sm font-semibold text-amber-900">Landlord's Installation Licence</h3>
                <p className="text-xs text-amber-800">
                  Rental premises require the landlord's EI Licence number. It will be
                  stored encrypted and visible only to the assigned LEW.
                </p>
                <Input
                  label="Landlord EI Licence No"
                  value={formData.landlordEiLicenceNo}
                  onChange={(e) => updateField('landlordEiLicenceNo', e.target.value)}
                  placeholder="e.g., E-12345 (leave blank if unknown)"
                  maxLength={100}
                />
                <p className="text-xs text-amber-700 mt-1">
                  If you don't have this number right now, you can submit and the
                  assigned LEW will collect it later.
                </p>
              </div>
            )}

            {/* ── P1.4: Declaration 3-group (EMA 조항 4개를 의미 단위로 축약) ── */}
            <div className="border-2 border-gray-200 rounded-lg p-4 space-y-3 bg-white">
              <h3 className="text-sm font-semibold text-gray-800">Declaration</h3>
              <p className="text-xs text-gray-500">
                All three must be acknowledged before submission.
              </p>
              <label className="flex items-start gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.declarationGroup1Accepted}
                  onChange={(e) => updateField('declarationGroup1Accepted', e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-sm text-gray-700">
                  I confirm that the information provided is <strong>true and complete</strong>,
                  and I understand that false declarations are subject to legal liability
                  under EMA regulations.
                </span>
              </label>
              <label className="flex items-start gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.declarationGroup2Accepted}
                  onChange={(e) => updateField('declarationGroup2Accepted', e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-sm text-gray-700">
                  My electrical installation <strong>complies with Singapore's electrical
                  safety regulations</strong> and SP Group technical requirements.
                </span>
              </label>
              <label className="flex items-start gap-2 cursor-pointer">
                <input
                  type="checkbox"
                  checked={formData.declarationGroup3Accepted}
                  onChange={(e) => updateField('declarationGroup3Accepted', e.target.checked)}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-primary-600 focus:ring-primary-500"
                />
                <span className="text-sm text-gray-700">
                  I authorize the assigned LEW to perform <strong>periodic inspections
                  and report results to EMA</strong> on my behalf.
                </span>
              </label>
            </div>
          </div>
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
          ) : (() => {
            // Declaration 3개 미체크 시에만 차단. Landlord EI 공란 허용 (LEW가 나중에 수집).
            const submitDisabled =
              !formData.declarationGroup1Accepted ||
              !formData.declarationGroup2Accepted ||
              !formData.declarationGroup3Accepted;
            return (
              <Button
                onClick={() => setShowSubmitConfirm(true)}
                loading={submitting}
                disabled={submitDisabled}
                aria-disabled={submitDisabled}
              >Submit Application</Button>
            );
          })()}
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
