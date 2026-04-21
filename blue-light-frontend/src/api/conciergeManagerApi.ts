/**
 * Concierge Manager 대시보드 API 클라이언트
 * - Kaki Concierge v1.5 Phase 1 PR#4 Stage B
 * - 권한: CONCIERGE_MANAGER / ADMIN / SYSTEM_ADMIN
 * - Backend: /api/concierge-manager/requests/**
 */

import axiosClient from './axiosClient';

// ── Types (Backend DTO와 일치) ──

export type ConciergeStatus =
  | 'SUBMITTED'
  | 'ASSIGNED'
  | 'CONTACTING'
  | 'QUOTE_SENT'
  | 'APPLICATION_CREATED'
  | 'AWAITING_APPLICANT_LOA_SIGN'
  | 'AWAITING_LICENCE_PAYMENT'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED';

export type NoteChannel = 'PHONE' | 'EMAIL' | 'WHATSAPP' | 'IN_PERSON' | 'OTHER';

export type ApplicantUserStatus =
  | 'PENDING_ACTIVATION'
  | 'ACTIVE'
  | 'SUSPENDED'
  | 'DELETED';

export interface ConciergeRequestSummary {
  conciergeRequestSeq: number;
  publicCode: string;
  submitterName: string;
  submitterEmail: string;
  submitterPhone: string;
  status: ConciergeStatus;
  slaBreached: boolean;
  assignedManagerSeq: number | null;
  assignedManagerName: string | null;
  applicationSeq: number | null;
  applicantUserStatus: ApplicantUserStatus | null;
  createdAt: string;
  firstContactAt: string | null;
}

export interface NoteResponse {
  conciergeNoteSeq: number;
  authorUserSeq: number;
  authorName: string;
  channel: NoteChannel;
  content: string;
  createdAt: string;
}

export interface ApplicantStatusInfo {
  userStatus: ApplicantUserStatus;
  emailVerified: boolean;
  activatedAt: string | null;
  firstLoggedInAt: string | null;
  hasActiveSetupToken: boolean;
  setupTokenExpiresAt: string | null;
}

export interface ConciergeRequestDetail extends ConciergeRequestSummary {
  memo: string | null;
  marketingOptIn: boolean;
  assignedAt: string | null;
  applicationCreatedAt: string | null;
  loaRequestedAt: string | null;
  loaSignedAt: string | null;
  licencePaidAt: string | null;
  completedAt: string | null;
  cancelledAt: string | null;
  cancellationReason: string | null;
  // Phase 1.5 — Quote workflow
  callScheduledAt: string | null;
  quotedAmount: number | null;
  quoteSentAt: string | null;
  verificationPhrase: string | null;
  notes: NoteResponse[];
  applicantStatus: ApplicantStatusInfo | null;
}

export interface SendQuotePayload {
  quotedAmount: number;
  callScheduledAt?: string | null;
  note?: string | null;
}

/** Spring Data Page 응답 */
export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export interface ListParams {
  status?: ConciergeStatus | '';
  q?: string;
  page?: number;
  size?: number;
}

export interface StatusTransitionPayload {
  nextStatus: ConciergeStatus;
  assignedManagerSeq?: number;
}

export interface NoteAddPayload {
  channel: NoteChannel;
  content: string;
}

export interface CancelPayload {
  reason: string;
}

// ── PR#5 Stage B: Application on-behalf 대리 생성 ──

/**
 * 대리 Application 생성 요청 페이로드.
 * Backend {@code CreateApplicationRequest}와 일치. Phase 1 MVP 기준 최소 필드만 정의.
 */
export interface CreateApplicationPayload {
  address: string;
  postalCode: string;
  buildingType?: string;
  selectedKva: number;
  applicantType: 'INDIVIDUAL' | 'CORPORATE';
  /** 기본 'NEW'. RENEWAL은 original licence 필드가 추가 필요. */
  applicationType?: 'NEW' | 'RENEWAL';
  spAccountNo?: string;
  /** 기본 'SELF_UPLOAD'. */
  sldOption?: 'SELF_UPLOAD' | 'REQUEST_LEW';
  kvaUnknown?: boolean;
}

export interface CreateOnBehalfResponse {
  applicationSeq: number;
  conciergeRequestSeq: number;
  /** Concierge 전이 후 상태 (APPLICATION_CREATED) */
  conciergeStatus: ConciergeStatus;
}

// ── API calls ──

const base = '/concierge-manager/requests';

export const listConciergeRequests = async (
  params: ListParams = {}
): Promise<Page<ConciergeRequestSummary>> => {
  const response = await axiosClient.get<Page<ConciergeRequestSummary>>(base, {
    params: {
      status: params.status || undefined,
      q: params.q || undefined,
      page: params.page ?? 0,
      size: params.size ?? 20,
    },
  });
  return response.data;
};

export const getConciergeRequestDetail = async (
  id: number
): Promise<ConciergeRequestDetail> => {
  const response = await axiosClient.get<ConciergeRequestDetail>(`${base}/${id}`);
  return response.data;
};

export const transitionConciergeStatus = async (
  id: number,
  payload: StatusTransitionPayload
): Promise<ConciergeRequestDetail> => {
  const response = await axiosClient.patch<ConciergeRequestDetail>(
    `${base}/${id}/status`,
    payload
  );
  return response.data;
};

export const addConciergeNote = async (
  id: number,
  payload: NoteAddPayload
): Promise<NoteResponse> => {
  const response = await axiosClient.post<NoteResponse>(
    `${base}/${id}/notes`,
    payload
  );
  return response.data;
};

export const resendConciergeSetupEmail = async (id: number): Promise<void> => {
  await axiosClient.post(`${base}/${id}/resend-setup-email`);
};

export const cancelConciergeRequest = async (
  id: number,
  payload: CancelPayload
): Promise<ConciergeRequestDetail> => {
  const response = await axiosClient.patch<ConciergeRequestDetail>(
    `${base}/${id}/cancel`,
    payload
  );
  return response.data;
};

/**
 * 견적 이메일 발송 (Phase 1.5).
 * CONTACTING 또는 QUOTE_SENT 상태에서만 성공. 성공 시 QUOTE_SENT로 전이 + 이메일 발송.
 */
export const sendConciergeQuote = async (
  id: number,
  payload: SendQuotePayload
): Promise<ConciergeRequestDetail> => {
  const response = await axiosClient.post<ConciergeRequestDetail>(
    `${base}/${id}/quote`,
    payload
  );
  return response.data;
};

/**
 * 대리 Application 생성 (★ PR#5 Stage B).
 * - CONTACTING 상태에서만 성공. 성공 시 ConciergeRequest는 APPLICATION_CREATED로 자동 전이.
 * - 에러 코드: INVALID_STATE_FOR_APPLICATION (409), CONCIERGE_NOT_ASSIGNED (403), 기타 400/500.
 */
export const createApplicationOnBehalf = async (
  id: number,
  payload: CreateApplicationPayload
): Promise<CreateOnBehalfResponse> => {
  const response = await axiosClient.post<CreateOnBehalfResponse>(
    `${base}/${id}/applications`,
    payload
  );
  return response.data;
};

export const conciergeManagerApi = {
  list: listConciergeRequests,
  getDetail: getConciergeRequestDetail,
  transitionStatus: transitionConciergeStatus,
  addNote: addConciergeNote,
  resendSetupEmail: resendConciergeSetupEmail,
  cancel: cancelConciergeRequest,
  createApplicationOnBehalf,
  sendQuote: sendConciergeQuote,
};

export default conciergeManagerApi;
