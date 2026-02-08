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
export type UserRole = 'APPLICANT' | 'ADMIN';

/**
 * 사용자 정보
 */
export interface User {
  userSeq: number;
  email: string;
  name: string;
  phone?: string;
  role: UserRole;
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
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'EXPIRED';

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
  licenseExpiryDate?: string;
  createdAt: string;
  updatedAt: string;
}

// ============================================
// File Types
// ============================================

/**
 * 파일 종류
 */
export type FileType = 'DRAWING_SLD' | 'SITE_PHOTO' | 'REPORT_PDF' | 'LICENSE_PDF';

/**
 * 첨부 파일
 */
export interface FileInfo {
  fileSeq: number;
  applicationSeq: number;
  fileType: FileType;
  fileUrl: string;
  originalFilename?: string;
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
}

/**
 * Admin application response (includes applicant info)
 */
export interface AdminApplication extends Application {
  userSeq: number;
  userName: string;
  userEmail: string;
  userPhone?: string;
}

/**
 * Admin dashboard summary
 */
export interface AdminDashboard {
  totalApplications: number;
  pendingPayment: number;
  paid: number;
  inProgress: number;
  completed: number;
  totalUsers: number;
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
