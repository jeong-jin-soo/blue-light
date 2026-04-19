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
  notes: NoteResponse[];
  applicantStatus: ApplicantStatusInfo | null;
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

export const conciergeManagerApi = {
  list: listConciergeRequests,
  getDetail: getConciergeRequestDetail,
  transitionStatus: transitionConciergeStatus,
  addNote: addConciergeNote,
  resendSetupEmail: resendConciergeSetupEmail,
  cancel: cancelConciergeRequest,
};

export default conciergeManagerApi;
