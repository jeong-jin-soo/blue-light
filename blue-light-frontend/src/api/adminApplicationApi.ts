import axiosClient from './axiosClient';
import type {
  AdminApplication,
  AdminDashboard,
  ApplicationStatus,
  CompleteApplicationRequest,
  EmaSubmissionResponse,
  FileInfo,
  FileType,
  KvaStatus,
  KvaSource,
  LicenseStatus,
  Page,
  Payment,
  PaymentConfirmRequest,
  RevisionRequest,
  SldRequest,
  UpdateStatusRequest,
} from '../types';
import type {
  ManualPaymentPayload,
  ManualPaymentResponse,
} from '../types/manualPayment';

// Phase 5 PR#3 — kVA 확정 API
export interface ConfirmKvaPayload {
  selectedKva: number;
  note?: string;
}

export interface ConfirmKvaResponse {
  applicationId: number;
  kvaStatus: KvaStatus;
  kvaSource: KvaSource;
  selectedKva: number;
  quoteAmount: number;
  kvaConfirmedBy: number | null;
  kvaConfirmedAt: string | null;
}

// ── Dashboard ──────────────────────────────

export const getDashboard = async (): Promise<AdminDashboard> => {
  const response = await axiosClient.get<AdminDashboard>('/admin/dashboard');
  return response.data;
};

// ── Applications ──────────────────────────────

export const getApplications = async (
  page = 0,
  size = 20,
  status?: ApplicationStatus,
  search?: string,
  kvaStatus?: KvaStatus,
  licenseStatus?: LicenseStatus
): Promise<Page<AdminApplication>> => {
  const response = await axiosClient.get<Page<AdminApplication>>('/admin/applications', {
    params: {
      page,
      size,
      ...(status && { status }),
      ...(search && { search }),
      ...(kvaStatus && { kvaStatus }),
      ...(licenseStatus && { licenseStatus }),
    },
  });
  return response.data;
};

export const getApplication = async (id: number): Promise<AdminApplication> => {
  const response = await axiosClient.get<AdminApplication>(`/admin/applications/${id}`);
  return response.data;
};

export const updateStatus = async (
  id: number,
  data: UpdateStatusRequest
): Promise<AdminApplication> => {
  const response = await axiosClient.patch<AdminApplication>(
    `/admin/applications/${id}/status`,
    data
  );
  return response.data;
};

/** 완료 건 재개(reopen) — ADMIN 전용. COMPLETED → IN_PROGRESS, APPLICATION_REOPENED 로 감사됨. */
export const reopenApplication = async (id: number): Promise<AdminApplication> => {
  const response = await axiosClient.post<AdminApplication>(
    `/admin/applications/${id}/reopen`
  );
  return response.data;
};

export const requestRevision = async (
  id: number,
  data: RevisionRequest
): Promise<AdminApplication> => {
  const response = await axiosClient.post<AdminApplication>(
    `/admin/applications/${id}/revision`,
    data
  );
  return response.data;
};

export const approveForPayment = async (id: number): Promise<AdminApplication> => {
  const response = await axiosClient.post<AdminApplication>(
    `/admin/applications/${id}/approve`
  );
  return response.data;
};

/**
 * Phase 5 PR#3 — LEW/ADMIN kVA 확정 (AC-A1).
 * force=true 는 ADMIN 전용 override.
 */
export const confirmKva = async (
  id: number,
  data: ConfirmKvaPayload,
  force = false
): Promise<ConfirmKvaResponse> => {
  const response = await axiosClient.patch<ConfirmKvaResponse>(
    `/admin/applications/${id}/kva`,
    data,
    { params: force ? { force: true } : {} }
  );
  return response.data;
};

// ── 결제 후 kVA 사후 변경 (PR-1) ──────────────────────────────
// 스펙: doc/Project Analysis/kva-postpayment-adjustment-spec.md §4.1

export interface KvaPostPaymentOverridePayload {
  newKva: number;
  reason: string;
  adminMemo?: string;
  paymentAdjustment?: string; // PENDING | PAID_DIFFERENCE | REFUNDED | WAIVED
  settledAmount?: number;
  receiptReferenceNumber?: string;
}

export interface KvaPostPaymentOverrideResponse {
  adjustmentSeq: number;
  previousKva: number;
  newKva: number;
  previousQuoteAmount: number;
  newQuoteAmount: number;
  amountDifference: number;
}

/**
 * 결제 후 kVA 사후 변경 (ADMIN 전용).
 * 결제 전 신청은 {@link confirmKva} 의 force=true 를 사용해야 한다.
 */
export const overrideKvaPostPayment = async (
  applicationSeq: number,
  data: KvaPostPaymentOverridePayload
): Promise<KvaPostPaymentOverrideResponse> => {
  const response = await axiosClient.post<KvaPostPaymentOverrideResponse>(
    `/admin/applications/${applicationSeq}/kva-override-postpayment`,
    data
  );
  return response.data;
};

// ── 결제 후 kVA 사후 변경 — 이력 + Settlement (PR-4) ──────────────────────
// 스펙: doc/Project Analysis/kva-postpayment-adjustment-spec.md §4.3 / §8 PR-4

/** PR-4: KvaAdjustmentRecord 의 status enum (백엔드 mirror). */
export type KvaAdjustmentStatus =
  | 'PENDING_ADMIN_REVIEW'
  | 'APPLIED'
  | 'RESOLVED_BY_ADMIN_OVERRIDE'
  | 'REJECTED'
  | 'CANCELLED';

/** PR-4: 변경 주체 역할 (ADMIN | LEW). */
export type KvaAdjustmentChangedByRole = 'ADMIN' | 'LEW';

/** PR-4: 정산 상태 enum (PENDING | PAID_DIFFERENCE | REFUNDED | WAIVED). */
export type KvaPaymentAdjustment =
  | 'PENDING'
  | 'PAID_DIFFERENCE'
  | 'REFUNDED'
  | 'WAIVED';

/**
 * PR-4: 이력 카드 row.
 *
 * <p>{@code lewRequestSeq} 가 있으면 ADMIN 변경 row 가 어떤 LEW 요청 row 에 응답한 것인지 self-FK
 * 로 가리킨다. 프론트는 이를 기준으로 timeline 에 그룹 표시.</p>
 */
export interface KvaAdjustmentHistoryItem {
  adjustmentSeq: number;
  /** KVA_CHANGE | SLD_ADDED — 조정 유형(견적 조정 원장 일반화). */
  adjustmentType?: 'KVA_CHANGE' | 'SLD_ADDED';
  status: KvaAdjustmentStatus;
  changedByRole: KvaAdjustmentChangedByRole;
  changedByUserName?: string;
  previousKva: number;
  newKva?: number;
  proposedKva?: number;
  previousQuoteAmount?: number;
  newQuoteAmount?: number;
  amountDifference?: number;
  reason: string;
  adminMemo?: string;
  paymentAdjustment?: KvaPaymentAdjustment;
  settledAmount?: number;
  receiptReferenceNumber?: string;
  settlementMemo?: string;
  settledAt?: string;
  lewRequestSeq?: number;
  createdAt: string;
  adminAdjustmentAt?: string;
}

/** PR-4: settlement 마킹 요청 payload. */
export interface KvaSettlementUpdatePayload {
  /** PAID_DIFFERENCE / REFUNDED / WAIVED. PENDING 은 백엔드에서 거부됨. */
  paymentAdjustment: 'PAID_DIFFERENCE' | 'REFUNDED' | 'WAIVED';
  settledAmount?: number;
  receiptReferenceNumber?: string;
  settlementMemo?: string;
  /** LEW 알림 발송 여부. 기본 true. */
  notifyLew?: boolean;
}

/**
 * PR-4: 결제 후 kVA 변경 이력 조회.
 *
 * <p>응답은 시간 내림차순. 빈 배열도 정상.</p>
 * <p>권한: ADMIN/SYSTEM_ADMIN 또는 신청에 배정된 LEW.</p>
 */
export const getKvaAdjustments = async (
  applicationSeq: number
): Promise<KvaAdjustmentHistoryItem[]> => {
  const response = await axiosClient.get<KvaAdjustmentHistoryItem[]>(
    `/admin/applications/${applicationSeq}/kva-adjustments`
  );
  return response.data;
};

/**
 * PR-4: settlement 마킹.
 *
 * 가드 위반 코드:
 * - 404 KVA_ADJUSTMENT_NOT_FOUND — row 미존재 또는 다른 application 의 row
 * - 409 KVA_SETTLEMENT_NOT_APPLICABLE — row.status 가 APPLIED/RESOLVED_BY_ADMIN_OVERRIDE 가 아님
 * - 409 KVA_SETTLEMENT_ALREADY_FINALIZED — D6 거부 (이미 PAID_DIFFERENCE/REFUNDED/WAIVED)
 * - 400 KVA_SETTLEMENT_INVALID_VALUE — paymentAdjustment 누락 또는 PENDING
 */
export const markKvaSettlement = async (
  applicationSeq: number,
  adjustmentSeq: number,
  payload: KvaSettlementUpdatePayload
): Promise<KvaAdjustmentHistoryItem> => {
  const response = await axiosClient.patch<KvaAdjustmentHistoryItem>(
    `/admin/applications/${applicationSeq}/kva-adjustments/${adjustmentSeq}/settlement`,
    payload
  );
  return response.data;
};

export const completeApplication = async (
  id: number,
  data: CompleteApplicationRequest
): Promise<AdminApplication> => {
  const response = await axiosClient.post<AdminApplication>(
    `/admin/applications/${id}/complete`,
    data
  );
  return response.data;
};

// ── Payments ──────────────────────────────

export const confirmPayment = async (
  applicationId: number,
  data: PaymentConfirmRequest
): Promise<Payment> => {
  const response = await axiosClient.post<Payment>(
    `/admin/applications/${applicationId}/payments/confirm`,
    data
  );
  return response.data;
};

export const getPayments = async (applicationId: number): Promise<Payment[]> => {
  const response = await axiosClient.get<Payment[]>(
    `/admin/applications/${applicationId}/payments`
  );
  return response.data;
};

// ── Manual Payment (★ Concierge 강화 + 별도 수금 PR-2/PR-4) ──────────────────────
// 스펙: doc/Project Analysis/concierge-flow-and-offline-payment-spec.md §7.3, AC-A1~A7

/**
 * ADMIN/SYSTEM_ADMIN 별도 수금 기록 (Application 결제).
 *
 * <p>D3=C: ADMIN 은 PENDING_REVIEW 부터 모든 상태에서 호출 가능 (단 PAID/IN_PROGRESS/COMPLETED 는
 * 백엔드에서 409 ALREADY_PAID 또는 INVALID_STATUS 차단).</p>
 *
 * <p>에러 코드:</p>
 * <ul>
 *   <li>400 INVALID_AMOUNT — amount 0 이하</li>
 *   <li>400 INVALID_PAYMENT_METHOD — PAYNOW_ONLINE 입력 시 (offline 4종만 허용)</li>
 *   <li>409 ALREADY_PAID — 이미 결제 완료</li>
 *   <li>409 INVALID_STATUS — 호출 불가 상태 (예: EXPIRED)</li>
 * </ul>
 */
export const recordManualPayment = async (
  applicationSeq: number,
  payload: ManualPaymentPayload,
): Promise<ManualPaymentResponse> => {
  const response = await axiosClient.post<ManualPaymentResponse>(
    `/admin/applications/${applicationSeq}/manual-payment`,
    payload,
  );
  return response.data;
};

// ── Files (admin) ──────────────────────────────

export const uploadFile = async (
  applicationId: number,
  file: File,
  fileType: FileType = 'LICENSE_PDF'
): Promise<FileInfo> => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('fileType', fileType);

  const response = await axiosClient.post<FileInfo>(
    `/admin/applications/${applicationId}/files`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

// ── EMA 제출 추적 (ema-submission-tracking-spec.md §7) ──────────────────────
// 전이 7종 + GET 모두 EmaSubmissionResponse 반환. 권한(LEW 본인 + ADMIN 대행)·전이 가드는 백엔드.
// 에러코드(토스트 매핑): EMA_NOT_APPROVED, LICENSE_PDF_MISSING, INVALID_EMA_TRANSITION,
//   EMA_ACK_REQUIRED, EMA_REFERENCE_REQUIRED, EMA_QUERY_NOTE_REQUIRED, EMA_NOT_IN_PROGRESS.

/** EMA 제출 추적 조회 (폴링/탭 갱신). */
export const getEmaSubmission = async (id: number): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.get<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema`
  );
  return response.data;
};

/** T1: NOT_SUBMITTED → SUBMITTED. */
export const markEmaSubmitted = async (
  id: number,
  emaReferenceNo: string
): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/submit`,
    { emaReferenceNo }
  );
  return response.data;
};

/** T2/T4: SUBMITTED/RESUBMITTED → QUERY_RAISED. */
export const raiseEmaQuery = async (
  id: number,
  queryNote: string
): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/query`,
    { queryNote }
  );
  return response.data;
};

/** T3/T10: QUERY_RAISED/REJECTED → RESUBMITTED. 접수번호는 선택(미전달 시 기존값 유지). */
export const resubmitEma = async (
  id: number,
  emaReferenceNo?: string
): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/resubmit`,
    emaReferenceNo ? { emaReferenceNo } : {}
  );
  return response.data;
};

/** T5/T6: SUBMITTED/RESUBMITTED → APPROVED. 발급(완료)과 분리된 상태 표기. */
export const approveEma = async (id: number): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/approve`
  );
  return response.data;
};

/** T7: SUBMITTED/RESUBMITTED → REJECTED. 사유는 선택. 종착 아님(T10 재진입). */
export const rejectEma = async (
  id: number,
  reason?: string
): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/reject`,
    reason ? { reason } : {}
  );
  return response.data;
};

/** T8: SUBMITTED/QUERY_RAISED/RESUBMITTED → WITHDRAWN. */
export const withdrawEma = async (id: number): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/withdraw`
  );
  return response.data;
};

/** T9: APPROVED/WITHDRAWN → 직전 상태 복원. ADMIN/SYSTEM_ADMIN 전용(오기입 정정). */
export const revertEmaDecision = async (id: number): Promise<EmaSubmissionResponse> => {
  const response = await axiosClient.post<EmaSubmissionResponse>(
    `/admin/applications/${id}/ema/revert`
  );
  return response.data;
};

// ── 활동 타임라인 (audit_logs SSOT) ──────────────────────────────
// GET /admin/applications/{id}/activity — ADMIN/SYSTEM_ADMIN 전용. 시간 오름차순.

/** 백엔드 AuditAction 미러 — 신청 타임라인에 등장하는 주요 액션 (그 외는 string 폴백). */
export type ActivityAction = string;

/** 신청 건별 활동 타임라인 항목 (audit_logs 1행). */
export interface ApplicationActivityItem {
  auditLogSeq: number;
  occurredAt: string;
  action: ActivityAction;
  actionCategory: 'AUTH' | 'APPLICATION' | 'ADMIN' | 'SYSTEM';
  actorSeq: number | null;
  actorEmail: string | null;
  actorRole: string | null;
  /** 자동(시스템/스케줄러) 동작이면 true. */
  system: boolean;
  description: string | null;
  beforeValue: string | null;
  afterValue: string | null;
  entityType: string | null;
  entityId: string | null;
  httpStatus: number | null;
}

/**
 * 신청 건별 활동 타임라인 조회 (ADMIN/SYSTEM_ADMIN).
 * 시간 오름차순(진행 순서). 빈 배열도 정상.
 */
export const getApplicationActivity = async (
  applicationSeq: number
): Promise<ApplicationActivityItem[]> => {
  const response = await axiosClient.get<ApplicationActivityItem[]>(
    `/admin/applications/${applicationSeq}/activity`
  );
  return response.data;
};

// ── SLD Request ──────────────────────────────

export const getAdminSldRequest = async (applicationId: number): Promise<SldRequest | null> => {
  const response = await axiosClient.get<SldRequest>(
    `/admin/applications/${applicationId}/sld-request`
  );
  return response.data;
};

export const uploadSldComplete = async (
  applicationId: number,
  fileSeq: number,
  lewNote?: string
): Promise<SldRequest> => {
  const response = await axiosClient.post<SldRequest>(
    `/admin/applications/${applicationId}/sld-uploaded`,
    { fileSeq, lewNote }
  );
  return response.data;
};

export const confirmSld = async (applicationId: number): Promise<SldRequest> => {
  const response = await axiosClient.post<SldRequest>(
    `/admin/applications/${applicationId}/sld-confirm`
  );
  return response.data;
};

export const unconfirmSld = async (applicationId: number): Promise<SldRequest> => {
  const response = await axiosClient.post<SldRequest>(
    `/admin/applications/${applicationId}/sld-unconfirm`
  );
  return response.data;
};
