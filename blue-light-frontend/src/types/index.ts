/**
 * Blue Light Frontend - Type Definitions
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
 * 사용자 역할
 */
export type UserRole = 'APPLICANT' | 'LEW' | 'ADMIN';

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
  name: string;
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
export type ApplicationType = 'NEW' | 'RENEWAL' | 'SUPPLY_INSTALLATION';

/**
 * SLD 제출 방식
 */
export type SldOption = 'SELF_UPLOAD' | 'REQUEST_LEW';

/**
 * SLD 요청 상태
 */
export type SldRequestStatus = 'REQUESTED' | 'UPLOADED' | 'CONFIRMED';

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
  createdAt: string;
  updatedAt: string;
}

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
  // Phase 19: Assigned LEW info
  assignedLewName?: string;
  assignedLewLicenceNo?: string;
  // SP Group 계정 번호
  spAccountNo?: string;
  // Phase 18: 갱신 + 견적 개선
  applicationType: ApplicationType;
  serviceFee?: number;
  originalApplicationSeq?: number;
  existingLicenceNo?: string;
  renewalReferenceNo?: string;
  existingExpiryDate?: string;
  renewalPeriodMonths?: number;
  emaFee?: number;
  // SLD 제출 방식
  sldOption?: SldOption;
}

// ============================================
// File Types
// ============================================

/**
 * 파일 종류
 */
export type FileType = 'DRAWING_SLD' | 'OWNER_AUTH_LETTER' | 'SITE_PHOTO' | 'REPORT_PDF' | 'LICENSE_PDF';

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
  name: string;
  phone?: string;
  role?: string;
  lewLicenceNo?: string;
  lewGrade?: string;
  companyName?: string;
  uen?: string;
  designation?: string;
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
  name: string;
  role: UserRole;
  approved: boolean;
  emailVerified: boolean;
}

// ============================================
// Application Form Types
// ============================================

/**
 * 신청서 작성 요청
 */
export interface CreateApplicationRequest {
  address: string;
  postalCode: string;
  buildingType?: string;
  selectedKva: number;
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
  serviceFee: number;
  totalAmount: number;
}

/**
 * Admin application response (includes applicant info)
 */
export interface AdminApplication extends Application {
  userSeq: number;
  userName: string;
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
  assignedLewName?: string;
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
  name: string;
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
  name: string;
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
