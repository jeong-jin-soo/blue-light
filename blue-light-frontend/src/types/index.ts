/**
 * LicenseKaki Frontend - Type Definitions
 * 백엔드 Entity와 일치하는 TypeScript Interface 정의
 */

// ============================================
// Common Types
// ============================================

/**
 * API 에러 응답
 */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  code: string;
  message: string;
  details?: Record<string, string>;
}

// ============================================
// User Types
// ============================================

/**
 * 사용자 역할 — 단일 정의원은 `constants/roles.ts`
 */
import type { UserRole } from '../constants/roles';
export type { UserRole };

/**
 * LEW 승인 상태
 */
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/**
 * LEW 등급 (Grade 7/8/9)
 */
export type LewGrade = 'GRADE_7' | 'GRADE_8' | 'GRADE_9';

/**
 * 사용자 정보
 */
export interface User {
  userSeq: number;
  email: string;
  firstName: string;
  lastName: string;
  phone?: string;
  role: UserRole;
  approved?: boolean;
  approvedStatus?: ApprovalStatus;
  lewLicenceNo?: string;
  lewGrade?: LewGrade;
  companyName?: string;
  uen?: string;
  designation?: string;
  correspondenceAddress?: string;
  correspondencePostalCode?: string;
  hasSignature?: boolean;
  pdpaConsentAt?: string;
  createdAt: string;
  updatedAt: string;
}

// ============================================
// Application Types
// ============================================

/**
 * 신청 상태
 */
export type ApplicationStatus =
  | 'PENDING_REVIEW'
  | 'REVISION_REQUESTED'
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'EXPIRED';

/**
 * 신청 유형
 */
export type ApplicationType = 'NEW' | 'RENEWAL';

/**
 * 신청자 유형 (Phase 1)
 */
export type ApplicantType = 'INDIVIDUAL' | 'CORPORATE';

/**
 * SLD 제출 방식
 * - SELF_UPLOAD: 신청자가 지금 업로드
 * - SUBMIT_WITHIN_3_MONTHS: 3개월 내 제출 약속 (JIT — 신청 시점에 SLD 준비 불필요)
 * - REQUEST_LEW: LEW에게 작성 의뢰
 */
export type SldOption = 'SELF_UPLOAD' | 'SUBMIT_WITHIN_3_MONTHS' | 'REQUEST_LEW';

/**
 * EMA ELISE Premises Type (시설 용도)
 */
export type PremisesType =
  | 'COMMERCIAL'
  | 'FACTORIES'
  | 'FARM'
  | 'RESIDENTIAL'
  | 'INDUSTRIAL'
  | 'HOTEL'
  | 'HEALTHCARE'
  | 'EDUCATION'
  | 'GOVERNMENT'
  | 'MIXED_USE'
  | 'OTHER';

/**
 * SLD 요청 상태
 */
export type SldRequestStatus = 'REQUESTED' | 'AI_GENERATING' | 'UPLOADED' | 'CONFIRMED';

/**
 * SLD 요청 정보
 */
export interface SldRequest {
  sldRequestSeq: number;
  applicationSeq: number;
  status: SldRequestStatus;
  applicantNote?: string;
  lewNote?: string;
  uploadedFileSeq?: number;
  sketchFileSeq?: number;
  createdAt: string;
  updatedAt: string;
}

/**
 * Phase 5 — kVA 확정 상태
 * - UNKNOWN: 신청자가 "I don't know" 선택, LEW 확정 대기
 * - CONFIRMED: 신청자 직접 입력(USER_INPUT) 또는 LEW 확정(LEW_VERIFIED)
 */
export type KvaStatus = 'UNKNOWN' | 'CONFIRMED';

/**
 * Phase 5 — kVA 출처
 */
export type KvaSource = 'USER_INPUT' | 'LEW_VERIFIED';

/**
 * 라이선스 신청 내역
 */
export interface Application {
  applicationSeq: number;
  userSeq: number;
  address: string;
  postalCode: string;
  buildingType?: string;
  selectedKva: number;
  quoteAmount: number;
  status: ApplicationStatus;
  licenseNumber?: string;
  reviewComment?: string;
  licenseExpiryDate?: string;
  createdAt: string;
  updatedAt: string;
  // Phase 5: kVA 확정 상태
  kvaStatus?: KvaStatus;
  kvaSource?: KvaSource;
  kvaConfirmedAt?: string;
  kvaConfirmedBy?: number;
  // Phase 19: Assigned LEW info
  assignedLewFirstName?: string;
  assignedLewLastName?: string;
  assignedLewLicenceNo?: string;
  // SP Group 계정 번호
  spAccountNo?: string;
  // Phase 1: 신청자 유형
  applicantType: ApplicantType;
  // 갱신 + 견적
  applicationType: ApplicationType;
  sldFee?: number;
  originalApplicationSeq?: number;
  existingLicenceNo?: string;
  renewalReferenceNo?: string;
  existingExpiryDate?: string;
  renewalPeriodMonths?: number;
  emaFee?: number;
  // SLD 제출 방식
  sldOption?: SldOption;
  // LOA 서명 정보
  loaSignatureUrl?: string;
  loaSignedAt?: string;
  // ── P1.2: EMA ELISE 필드 (신청자 수집 / 일부는 LEW 추가 예정) ──
  installationName?: string;
  premisesType?: PremisesType;
  isRentalPremises?: boolean;
  /** Landlord EI Licence 는 서버 응답에서 앞 5자 마스킹 처리됨 (LEW 전용 응답에서만 원본). */
  landlordEiLicenceMasked?: string;
  renewalCompanyNameChanged?: boolean;
  renewalAddressChanged?: boolean;
  installationAddressBlock?: string;
  installationAddressUnit?: string;
  installationAddressStreet?: string;
  installationAddressBuilding?: string;
  installationAddressPostalCode?: string;
  correspondenceAddressBlock?: string;
  correspondenceAddressUnit?: string;
  correspondenceAddressStreet?: string;
  correspondenceAddressBuilding?: string;
  correspondenceAddressPostalCode?: string;
  // ── P2.A: LEW Review Form hint 응답 필드 (스펙 §5.5) ──
  /** MSSL hint — last4만 노출(평문·enc는 LEW 전용 응답에서만). */
  msslHintLast4?: string;
  supplyVoltageHint?: number;
  consumerTypeHint?: string;
  retailerHint?: string;
  hasGeneratorHint?: boolean;
  generatorCapacityHint?: number;
  /** CoF finalize 여부 — 상세 화면 "CoF 발급됨" 배지용 (P2.C에서 사용). */
  cofFinalized?: boolean;
  cofCertifiedAt?: string;
  /** 경고 수준 검증 결과. 200 OK 차단하지 않음. */
  warnings?: ApplicantHintWarning[];
}

/**
 * 신청자 hint 필드에 대한 경고 수준 검증 결과.
 * 백엔드 {@code ApplicantHintWarning} DTO와 1:1 매핑.
 */
export interface ApplicantHintWarning {
  field: string;
  code: string;
  reason: string;
}

/** Declaration consent types — Submit 시 3건 append-only 로그에 기록. */
export type DeclarationConsentType =
  | 'APPLICATION_DECLARATION_V1_GROUP1'
  | 'APPLICATION_DECLARATION_V1_GROUP2'
  | 'APPLICATION_DECLARATION_V1_GROUP3';

// ============================================
// File Types
// ============================================

/**
 * 파일 종류
 */
export type FileType = 'DRAWING_SLD' | 'OWNER_AUTH_LETTER' | 'SITE_PHOTO' | 'REPORT_PDF' | 'LICENSE_PDF' | 'PAYMENT_RECEIPT' | 'SP_ACCOUNT_DOC' | 'SKETCH_SLD' | 'CIRCUIT_SCHEDULE';

/**
 * 첨부 파일
 */
export interface FileInfo {
  fileSeq: number;
  applicationSeq: number;
  fileType: FileType;
  fileUrl: string;
  originalFilename?: string;
  fileSize?: number;
  uploadedAt: string;
}

/**
 * 샘플 파일 (관리자가 업로드한 참고용 파일)
 */
export interface SampleFileInfo {
  sampleFileSeq: number;
  categoryKey: string;
  originalFilename: string;
  fileSize: number;
  uploadedAt: string;
}

// ============================================
// Inspection Types
// ============================================

/**
 * 점검 체크리스트 항목
 */
export interface ChecklistItem {
  id: string;
  label: string;
  checked: boolean;
  comment?: string;
}

/**
 * 현장 점검 결과
 */
export interface Inspection {
  inspectionSeq: number;
  applicationSeq: number;
  inspectorUserSeq: number;
  checklistData?: ChecklistItem[];
  inspectorComment?: string;
  signatureUrl?: string;
  inspectedAt: string;
  updatedAt: string;
}

// ============================================
// Invoice Types (E-Invoice)
// ============================================

/**
 * E-Invoice 메타데이터.
 * PDF 바이너리는 {@code pdfFileSeq} 를 기존 {@code /api/files/{id}/download} 로 조합해 받는다.
 */
export interface Invoice {
  invoiceSeq: number;
  invoiceNumber: string;
  paymentSeq: number;
  referenceType: string;
  referenceSeq: number;
  applicationSeq?: number;
  issuedAt: string;
  totalAmount: number;
  currency: string;
  pdfFileSeq: number;
  billingRecipientName: string;
  billingRecipientCompany?: string;
}

/** Admin 재발행 요청 — 스냅샷은 불변, PDF만 재생성. 사유 필수. */
export interface RegenerateInvoiceRequest {
  reason: string;
}

// ============================================
// Price Types
// ============================================

/**
 * 용량별 단가
 */
export interface MasterPrice {
  masterPriceSeq: number;
  description?: string;
  kvaMin: number;
  kvaMax: number;
  price: number;
  renewalPrice: number;
  isActive: boolean;
}

// ============================================
// Payment Types
// ============================================

/**
 * 결제 상태
 */
export type PaymentStatus = 'SUCCESS' | 'FAILED' | 'REFUNDED';

/**
 * 결제 정보
 */
export interface Payment {
  paymentSeq: number;
  applicationSeq: number;
  transactionId?: string;
  amount: number;
  paymentMethod: string;
  status: PaymentStatus;
  paidAt: string;
  updatedAt: string;
}

// ============================================
// Auth Types
// ============================================

/**
 * 로그인 요청
 */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * 회원가입 요청
 */
export interface SignupRequest {
  email: string;
  password: string;
  firstName: string;
  lastName: string;
  role?: string;
  lewLicenceNo?: string;
  lewGrade?: string;
  pdpaConsent: boolean;
}

/**
 * 토큰 응답
 */
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  userSeq: number;
  email: string;
  firstName: string;
  lastName: string;
  role: UserRole;
  approved: boolean;
  emailVerified: boolean;
}

// ============================================
// Application Form Types
// ============================================

/**
 * Phase 2 PR#3: 법인 JIT 모달에서 수집하는 회사 정보
 */
export interface CompanyInfo {
  companyName: string;
  uen: string;
  designation: string;
  /** true(기본) = User 프로필에 저장 / false = 이 신청에만 사용 */
  persistToProfile: boolean;
}

/**
 * 신청서 작성 요청
 */
export interface CreateApplicationRequest {
  address: string;
  postalCode: string;
  buildingType?: string;
  selectedKva: number;
  applicantType: ApplicantType;
  spAccountNo?: string;
  // Phase 18: 갱신 관련
  applicationType?: string;
  originalApplicationSeq?: number;
  existingLicenceNo?: string;
  existingExpiryDate?: string;
  renewalPeriodMonths?: number;
  renewalReferenceNo?: string;
  // SLD 제출 방식
  sldOption?: string;
  // Phase 2 PR#3: 법인 JIT
  companyInfo?: CompanyInfo;
  // Phase 5: kVA UNKNOWN 플래그 — true면 서버가 selectedKva=45 강제
  kvaUnknown?: boolean;
  // ── P1.2: EMA ELISE 확장 필드 (전부 선택, JIT) ──
  installationName?: string;
  premisesType?: PremisesType;
  isRentalPremises?: boolean;
  landlordEiLicenceNo?: string;
  renewalCompanyNameChanged?: boolean;
  renewalAddressChanged?: boolean;
  installationAddressBlock?: string;
  installationAddressUnit?: string;
  installationAddressStreet?: string;
  installationAddressBuilding?: string;
  installationAddressPostalCode?: string;
  correspondenceAddressBlock?: string;
  correspondenceAddressUnit?: string;
  correspondenceAddressStreet?: string;
  correspondenceAddressBuilding?: string;
  correspondenceAddressPostalCode?: string;
  /** 제출 시점 폼 스냅샷 해시 (Declaration 감사 로그용). 미제공 시 서버가 재계산. */
  formSnapshotHash?: string;
  // ── P2.A: LEW Review Form hint 필드 (모두 optional, warning-only 검증) ──
  /** MSSL Account No 평문 힌트 (예: "123-45-6789-0"). */
  msslHint?: string;
  /** 공급 전압 힌트(V): 230 / 400 / 6600 / 22000. */
  supplyVoltageHint?: number;
  /** Consumer Type 힌트. */
  consumerTypeHint?: 'NON_CONTESTABLE' | 'CONTESTABLE';
  /** Retailer 힌트: RetailerCode enum 문자열. */
  retailerHint?: string;
  /** 발전기 보유 여부 힌트. */
  hasGeneratorHint?: boolean;
  /** 발전기 용량 힌트(kVA). */
  generatorCapacityHint?: number;
}

/**
 * 신청서 수정 요청 (보완 후 재제출)
 */
export interface UpdateApplicationRequest {
  address: string;
  postalCode: string;
  buildingType?: string;
  selectedKva: number;
  spAccountNo?: string;
  renewalPeriodMonths?: number;
  // ── P2.A: LEW Review Form hint 필드 ──
  msslHint?: string;
  supplyVoltageHint?: number;
  consumerTypeHint?: 'NON_CONTESTABLE' | 'CONTESTABLE';
  retailerHint?: string;
  hasGeneratorHint?: boolean;
  generatorCapacityHint?: number;
  // ── EMA ELISE 5-part 주소 (재제출 시 갱신) ──
  installationAddressBlock?: string;
  installationAddressUnit?: string;
  installationAddressStreet?: string;
  installationAddressBuilding?: string;
  installationAddressPostalCode?: string;
  correspondenceAddressBlock?: string;
  correspondenceAddressUnit?: string;
  correspondenceAddressStreet?: string;
  correspondenceAddressBuilding?: string;
  correspondenceAddressPostalCode?: string;
}

/**
 * 보완 요청 DTO
 */
export interface RevisionRequest {
  comment: string;
}

/**
 * 역할 변경 요청 (Admin)
 */
export interface ChangeRoleRequest {
  role: string;
}

/**
 * 신청서 응답 (상세)
 */
export interface ApplicationDetail extends Application {
  user?: User;
  files?: FileInfo[];
  inspection?: Inspection;
  payments?: Payment[];
}

// ============================================
// API Response Types (matching backend DTOs)
// ============================================

/**
 * Application summary for dashboard
 */
export interface ApplicationSummary {
  total: number;
  pendingReview: number;
  pendingPayment: number;
  inProgress: number;
  completed: number;
}

/**
 * Price calculation result
 */
export interface PriceCalculation {
  kva: number;
  tierDescription: string;
  price: number;
  sldFee: number;
  totalAmount: number;
  emaFee?: number;
}

/**
 * Admin application response (includes applicant info)
 */
export interface AdminApplication extends Application {
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  userPhone?: string;
  userCompanyName?: string;
  userUen?: string;
  userDesignation?: string;
  userCorrespondenceAddress?: string;
  userCorrespondencePostalCode?: string;
  reviewComment?: string;
  spAccountNo?: string;
  // Assigned LEW info
  assignedLewSeq?: number;
  assignedLewFirstName?: string;
  assignedLewLastName?: string;
  assignedLewEmail?: string;
  assignedLewLicenceNo?: string;
  assignedLewGrade?: LewGrade;
  assignedLewMaxKva?: number;
}

/**
 * Admin dashboard summary
 */
export interface AdminDashboard {
  totalApplications: number;
  pendingReview: number;
  revisionRequested: number;
  pendingPayment: number;
  paid: number;
  inProgress: number;
  completed: number;
  expired: number;
  totalUsers: number;
  unassigned: number;
}

/**
 * Paginated response
 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

/**
 * Update profile request
 */
export interface UpdateProfileRequest {
  firstName: string;
  lastName: string;
  phone?: string;
  lewLicenceNo?: string;
  lewGrade?: string;
  companyName?: string;
  uen?: string;
  designation?: string;
  correspondenceAddress?: string;
  correspondencePostalCode?: string;
}

/**
 * Change password request
 */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/**
 * Update status request (admin)
 */
export interface UpdateStatusRequest {
  status: ApplicationStatus;
}

/**
 * Complete application request (admin)
 */
export interface CompleteApplicationRequest {
  licenseNumber: string;
  licenseExpiryDate: string;
}

/**
 * Payment confirm request (admin)
 */
export interface PaymentConfirmRequest {
  transactionId?: string;
  paymentMethod?: string;
}

// ============================================
// LEW Assignment Types
// ============================================

/**
 * LEW 할당 요청
 */
export interface AssignLewRequest {
  lewUserSeq: number;
}

/**
 * LEW 요약 정보 (할당 드롭다운용)
 */
export interface LewSummary {
  userSeq: number;
  firstName: string;
  lastName: string;
  email: string;
  lewLicenceNo?: string;
  lewGrade?: LewGrade;
  maxKva?: number;
}

/**
 * 회원가입 옵션 (가입 가능한 역할 목록)
 */
export interface SignupOptions {
  availableRoles: string[];
  lewRegistrationOpen: boolean;
}

/**
 * 비밀번호 재설정 요청 (이메일)
 */
export interface ForgotPasswordRequest {
  email: string;
}

/**
 * 비밀번호 재설정 실행 (토큰 + 새 비밀번호)
 */
export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

/**
 * 메시지 응답 (forgot/reset password)
 */
export interface MessageResponse {
  message: string;
}

// ============================================
// Admin Price Management Types
// ============================================

/**
 * Admin 가격 티어 응답 (isActive, updatedAt 포함)
 */
export interface AdminPriceResponse {
  masterPriceSeq: number;
  description?: string;
  kvaMin: number;
  kvaMax: number;
  price: number;
  renewalPrice: number;
  sldPrice: number;
  isActive: boolean;
  updatedAt: string;
}

/**
 * 가격 티어 수정 요청
 */
export interface UpdatePriceRequest {
  price: number;
  description?: string;
  kvaMin?: number;
  kvaMax?: number;
  isActive?: boolean;
}

/**
 * 배치 가격 티어 항목 (생성/수정 겸용)
 */
export interface BatchPriceTierItem {
  masterPriceSeq: number | null;
  description: string;
  kvaMin: number;
  kvaMax: number;
  price: number;
  renewalPrice: number;
  sldPrice: number;
  isActive: boolean;
}

/**
 * 배치 가격 수정 요청
 */
export interface BatchUpdatePricesRequest {
  tiers: BatchPriceTierItem[];
}

// ============================================
// LOA Types
// ============================================

/**
 * LOA 상태 응답
 */
export interface LoaStatus {
  applicationSeq: number;
  loaGenerated: boolean;
  loaSigned: boolean;
  loaSignedAt?: string;
  loaFileSeq?: number;
  applicationType: ApplicationType;
}

// ============================================
// Chat Types
// ============================================

export type ChatMessageRole = 'user' | 'assistant';

export interface ChatMessage {
  id: string;
  role: ChatMessageRole;
  content: string;
  timestamp: Date;
}

export interface ChatRequest {
  message: string;
  sessionId?: string;
  history?: { role: string; content: string }[];
}

export interface ChatResponse {
  message: string;
  suggestedQuestions?: string[];
}

// ============================================
// SLD Chat Types
// ============================================

// ============================================
// SLD Order Types (SLD 전용 주문)
// ============================================

/**
 * SLD 전용 주문 상태
 */
export type SldOrderStatus =
  | 'PENDING_QUOTE'
  | 'QUOTE_PROPOSED'
  | 'QUOTE_REJECTED'
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'IN_PROGRESS'
  | 'SLD_UPLOADED'
  | 'REVISION_REQUESTED'
  | 'COMPLETED';

/**
 * SLD 전용 주문
 */
export interface SldOrder {
  sldOrderSeq: number;
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  ampere?: string;
  applicantNote?: string;
  sketchFileSeq?: number;
  status: SldOrderStatus;
  quoteAmount?: number;
  quoteNote?: string;
  managerNote?: string;
  uploadedFileSeq?: number;
  revisionComment?: string;
  assignedManagerSeq?: number;
  assignedManagerFirstName?: string;
  assignedManagerLastName?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * SLD 주문 생성 요청
 */
export interface CreateSldOrderRequest {
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  ampere?: string;
  applicantNote?: string;
}

/**
 * SLD 주문 견적 제안 요청
 */
export interface ProposeQuoteRequest {
  quoteAmount: number;
  quoteNote?: string;
}

/**
 * SLD Manager 대시보드
 */
export interface SldOrderDashboard {
  total: number;
  pendingQuote: number;
  quoteProposed: number;
  pendingPayment: number;
  paid: number;
  inProgress: number;
  sldUploaded: number;
  completed: number;
}

/**
 * SLD 주문 결제 정보
 */
export interface SldOrderPayment {
  sldOrderPaymentSeq: number;
  sldOrderSeq: number;
  amount: number;
  paymentMethod: string;
  status: string;
  paidAt: string;
  transactionId?: string;
}

export interface SldChatMessage {
  sldChatMessageSeq: number;
  applicationSeq: number;
  role: 'user' | 'assistant';
  content: string;
  metadata?: string;
  createdAt: string;
}

// ── Lighting Layout / Power Socket / LEW Service 주문 (SldOrder와 동일 구조) ──
// 프로세스는 SLD와 동일하며, 관리 항목은 차후 기능별로 분화될 수 있음.

export type LightingOrderStatus = SldOrderStatus;

export interface LightingOrder {
  lightingOrderSeq: number;
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
  sketchFileSeq?: number;
  status: LightingOrderStatus;
  quoteAmount?: number;
  quoteNote?: string;
  managerNote?: string;
  uploadedFileSeq?: number;
  revisionComment?: string;
  assignedManagerSeq?: number;
  assignedManagerFirstName?: string;
  assignedManagerLastName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateLightingOrderRequest {
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
}

/**
 * Lighting Manager 대시보드 (백엔드 LightingOrderDashboardResponse)
 */
export interface LightingOrderDashboard {
  total: number;
  pendingQuote: number;
  quoteProposed: number;
  pendingPayment: number;
  paid: number;
  inProgress: number;
  deliverableUploaded: number;
  completed: number;
}

export interface LightingOrderPayment {
  lightingOrderPaymentSeq: number;
  lightingOrderSeq: number;
  amount: number;
  paymentMethod: string;
  status: string;
  paidAt: string;
  transactionId?: string;
}

export type PowerSocketOrderStatus = SldOrderStatus;

export interface PowerSocketOrder {
  powerSocketOrderSeq: number;
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
  sketchFileSeq?: number;
  status: PowerSocketOrderStatus;
  quoteAmount?: number;
  quoteNote?: string;
  managerNote?: string;
  uploadedFileSeq?: number;
  revisionComment?: string;
  assignedManagerSeq?: number;
  assignedManagerFirstName?: string;
  assignedManagerLastName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePowerSocketOrderRequest {
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
}

/**
 * PowerSocket Manager 대시보드 (백엔드 PowerSocketOrderDashboardResponse)
 */
export interface PowerSocketOrderDashboard {
  total: number;
  pendingQuote: number;
  quoteProposed: number;
  pendingPayment: number;
  paid: number;
  inProgress: number;
  deliverableUploaded: number;
  completed: number;
}

export interface PowerSocketOrderPayment {
  powerSocketOrderPaymentSeq: number;
  powerSocketOrderSeq: number;
  amount: number;
  paymentMethod: string;
  status: string;
  paidAt: string;
  transactionId?: string;
}

/**
 * LEW Service 주문 상태 (방문형 서비스 기준 — PR 3 rename).
 * <p>SldOrderStatus 와 공유하지 않는다(Backend 에서 enum 분리).
 */
export type LewServiceOrderStatus =
  | 'PENDING_QUOTE'
  | 'QUOTE_PROPOSED'
  | 'QUOTE_REJECTED'
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'VISIT_SCHEDULED'
  | 'VISIT_COMPLETED'
  | 'REVISIT_REQUESTED'
  | 'COMPLETED';

/**
 * LEW Service 방문 사진 (PR 3).
 */
export interface VisitPhoto {
  photoSeq: number;
  fileSeq: number;
  caption?: string;
  uploadedAt: string;
}

export interface LewServiceOrder {
  lewServiceOrderSeq: number;
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
  sketchFileSeq?: number;
  status: LewServiceOrderStatus;
  /**
   * 파생 상태: status=VISIT_SCHEDULED && checkInAt 있음.
   * 백엔드에서 계산되어 내려옴.
   */
  onSite?: boolean;
  quoteAmount?: number;
  quoteNote?: string;
  managerNote?: string;
  /** PR 3 — 방문 보고서 (기존 uploadedFileSeq 와 같은 값, alias) */
  visitReportFileSeq?: number;
  /** @deprecated PR 3 — visitReportFileSeq 사용 권장 */
  uploadedFileSeq?: number;
  /** PR 3 — 재방문 요청 사유 */
  revisitComment?: string;
  /** @deprecated PR 3 — revisitComment 사용 권장 */
  revisionComment?: string;
  // LEW Service 방문형 리스키닝 PR 2 — 방문 일정 예약
  visitScheduledAt?: string;
  visitScheduleNote?: string;
  // PR 3 — 체크인/아웃 + 방문 사진
  checkInAt?: string;
  checkOutAt?: string;
  visitPhotos?: VisitPhoto[];
  assignedManagerSeq?: number;
  assignedManagerFirstName?: string;
  assignedManagerLastName?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * LEW Service check-out 요청 (Manager 측, PR 3).
 * POST /api/lew-service-manager/orders/{id}/check-out
 */
export interface CheckOutRequest {
  visitReportFileSeq: number;
  managerNote?: string;
}

/**
 * LEW Service 방문 일정 예약 요청 (Manager 측)
 * POST /api/lew-service-manager/orders/{id}/schedule-visit
 */
export interface ScheduleVisitRequest {
  /** ISO-8601 LocalDateTime (예: "2026-04-23T14:00:00") */
  visitScheduledAt: string;
  visitScheduleNote?: string;
}

export interface CreateLewServiceOrderRequest {
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
}

/**
 * LewService Manager 대시보드 (백엔드 LewServiceOrderDashboardResponse).
 * <p>PR 3 — 신규 visit* 필드 + 하위호환 alias.
 */
export interface LewServiceOrderDashboard {
  total: number;
  pendingQuote: number;
  quoteProposed: number;
  pendingPayment: number;
  paid: number;
  /** PR 3 — VISIT_SCHEDULED 건수 */
  visitScheduled: number;
  /** PR 3 — VISIT_COMPLETED 건수 */
  visitCompleted: number;
  /** PR 3 — REVISIT_REQUESTED 건수 */
  revisitRequested: number;
  completed: number;
  /** @deprecated PR 3 — visitScheduled 사용 권장 */
  inProgress?: number;
  /** @deprecated PR 3 — visitCompleted 사용 권장 */
  deliverableUploaded?: number;
}

export interface LewServiceOrderPayment {
  lewServiceOrderPaymentSeq: number;
  lewServiceOrderSeq: number;
  amount: number;
  paymentMethod: string;
  status: string;
  paidAt: string;
  transactionId?: string;
}

// ────────────────────────────────────────────────────────────
//  Expired License Order (LEW Service 와 동일 생애주기, 다중 참고 문서 업로드)
// ────────────────────────────────────────────────────────────

export type ExpiredLicenseOrderStatus = LewServiceOrderStatus;

export interface ExpiredLicenseVisitPhoto {
  photoSeq: number;
  fileSeq: number;
  caption?: string;
  uploadedAt: string;
}

export interface ExpiredLicenseSupportingDocument {
  fileSeq: number;
  fileType: string;
  originalFilename?: string;
  fileSize?: number;
  uploadedAt: string;
}

export interface ExpiredLicenseOrder {
  expiredLicenseOrderSeq: number;
  userSeq: number;
  userFirstName: string;
  userLastName: string;
  userEmail: string;
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
  status: ExpiredLicenseOrderStatus;
  onSite?: boolean;
  quoteAmount?: number;
  quoteNote?: string;
  managerNote?: string;
  visitReportFileSeq?: number;
  revisitComment?: string;
  visitScheduledAt?: string;
  visitScheduleNote?: string;
  checkInAt?: string;
  checkOutAt?: string;
  visitPhotos?: ExpiredLicenseVisitPhoto[];
  supportingDocuments?: ExpiredLicenseSupportingDocument[];
  assignedManagerSeq?: number;
  assignedManagerFirstName?: string;
  assignedManagerLastName?: string;
  createdAt: string;
  updatedAt: string;
}

export interface CreateExpiredLicenseOrderRequest {
  address?: string;
  postalCode?: string;
  buildingType?: string;
  selectedKva?: number;
  applicantNote?: string;
}

export interface ExpiredLicenseOrderDashboard {
  total: number;
  pendingQuote: number;
  quoteProposed: number;
  pendingPayment: number;
  paid: number;
  visitScheduled: number;
  visitCompleted: number;
  revisitRequested: number;
  completed: number;
}

export interface ExpiredLicenseOrderPayment {
  expiredLicenseOrderPaymentSeq: number;
  expiredLicenseOrderSeq: number;
  amount: number;
  paymentMethod: string;
  status: string;
  paidAt: string;
  transactionId?: string;
}

/**
 * SLD SSE 이벤트 타입
 */
export type SldSseEventType =
  | 'token'
  | 'tool_start'
  | 'tool_result'
  | 'sld_preview'
  | 'file_generated'
  | 'applied_defaults'
  | 'layout_warnings'
  | 'done'
  | 'error'
  | 'status'
  | 'model_switch'
  | 'heartbeat'
  | 'session'
  | 'template_matched'
  | 'progress';

/**
 * Progress 단계 (AI 요청 생명주기)
 */
export type SldProgressStage =
  | 'initializing'
  | 'analyzing'
  | 'gathering'
  | 'matching'
  | 'extracting'
  | 'validating'
  | 'generating'
  | 'previewing'
  | 'responding'
  | 'retrying'
  | 'completed'
  | 'error';

export interface SldSseEvent {
  type: SldSseEventType;
  content?: string;
  tool?: string;
  description?: string;
  summary?: string;
  svg?: string;
  fileId?: string;
  from_model?: string;
  to_model?: string;
  // progress 이벤트 필드
  stage?: SldProgressStage;
  message?: string;
  elapsed?: number;
  // applied_defaults / layout_warnings 이벤트 필드
  items?: string[];
}

// ============================================
// Audit Log Types
// ============================================

export type AuditAction =
  | 'LOGIN_SUCCESS' | 'LOGIN_FAILURE' | 'SIGNUP'
  | 'PASSWORD_RESET_REQUEST' | 'PASSWORD_RESET_COMPLETE' | 'EMAIL_VERIFIED'
  | 'APPLICATION_CREATED' | 'APPLICATION_UPDATED' | 'APPLICATION_STATUS_CHANGE'
  | 'APPLICATION_REVISION_REQUESTED' | 'APPLICATION_APPROVED' | 'APPLICATION_COMPLETED'
  | 'APPLICATION_RESUBMITTED'
  | 'FILE_UPLOADED' | 'FILE_DELETED'
  | 'LEW_APPROVED' | 'LEW_REJECTED' | 'USER_ROLE_CHANGED'
  | 'PAYMENT_CONFIRMED' | 'LEW_ASSIGNED' | 'LEW_UNASSIGNED'
  | 'SYSTEM_PROMPT_UPDATED' | 'SYSTEM_PROMPT_RESET'
  | 'GEMINI_KEY_UPDATED' | 'GEMINI_KEY_CLEARED'
  | 'EMAIL_VERIFICATION_TOGGLED' | 'PRICE_UPDATED' | 'SETTINGS_UPDATED'
  | 'DATA_EXPORTED' | 'ACCOUNT_DELETED' | 'PDPA_CONSENT_WITHDRAWN'
  | 'DATA_BREACH_REPORTED' | 'DATA_BREACH_PDPC_NOTIFIED'
  | 'DATA_BREACH_USERS_NOTIFIED' | 'DATA_BREACH_RESOLVED';

export type AuditCategory = 'AUTH' | 'APPLICATION' | 'ADMIN' | 'SYSTEM' | 'DATA_PROTECTION';

export interface AuditLog {
  auditLogSeq: number;
  userSeq?: number;
  userEmail?: string;
  userRole?: string;
  action: AuditAction;
  actionCategory: AuditCategory;
  entityType?: string;
  entityId?: string;
  description?: string;
  beforeValue?: string;
  afterValue?: string;
  ipAddress?: string;
  requestMethod?: string;
  requestUri?: string;
  httpStatus?: number;
  createdAt: string;
}

// ── Notification ──────────────────────────────

export type NotificationType =
  | 'PAYMENT_CONFIRMED'
  // PR4 — ADMIN이 결제를 확인하면 배정된 LEW에게 발송되는 알림
  | 'PAYMENT_CONFIRMED_LEW'
  // Phase 3 — LEW 서류 요청 워크플로
  | 'DOCUMENT_REQUEST_CREATED'
  | 'DOCUMENT_REQUEST_FULFILLED'
  | 'DOCUMENT_REQUEST_APPROVED'
  | 'DOCUMENT_REQUEST_REJECTED'
  // Phase 5 — LEW kVA 확정 알림
  | 'KVA_CONFIRMED';

// ── Phase 2 Document Management (re-export) ───
export type {
  DocumentRequestStatus,
  DocumentType,
  DocumentRequest,
  VoluntaryUploadResponse,
  VoluntaryUploadPayload,
} from './document';

export interface AppNotification {
  notificationSeq: number;
  type: NotificationType;
  title: string;
  message: string;
  referenceType?: string;
  referenceId?: number;
  isRead: boolean;
  read: boolean;
  readAt?: string;
  createdAt: string;
}
