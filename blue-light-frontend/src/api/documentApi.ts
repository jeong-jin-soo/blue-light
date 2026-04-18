/**
 * Phase 2 — Document Management API client
 * 백엔드 PR#1 엔드포인트 (Phase 2 spec §4):
 *   GET    /api/document-types
 *   GET    /api/applications/{id}/document-requests
 *   POST   /api/applications/{id}/documents           (multipart)
 *   DELETE /api/applications/{id}/documents/{docReqId}
 */

import axiosClient from './axiosClient';
import type {
  DocumentType,
  DocumentRequest,
  DocumentRequestStatus,
  VoluntaryUploadPayload,
  VoluntaryUploadResponse,
  CreateDocumentRequestItem,
  CreateDocumentRequestsResponse,
  DocumentRequestDecisionResponse,
} from '../types/document';

/**
 * 활성 Document Type 카탈로그 조회 (display_order 오름차순)
 */
export const getDocumentTypes = async (): Promise<DocumentType[]> => {
  const response = await axiosClient.get<DocumentType[]>('/document-types');
  return response.data;
};

/**
 * 신청서의 DocumentRequest 목록 조회
 * @param applicationSeq 신청 PK
 * @param status 선택적 status 필터
 */
export const getDocumentRequests = async (
  applicationSeq: number,
  status?: DocumentRequestStatus,
): Promise<DocumentRequest[]> => {
  const response = await axiosClient.get<DocumentRequest[]>(
    `/applications/${applicationSeq}/document-requests`,
    { params: status ? { status } : undefined },
  );
  return response.data;
};

/**
 * 자발적 업로드 (multipart)
 * OTHER 타입은 customLabel 필수.
 */
export const uploadVoluntaryDocument = async (
  applicationSeq: number,
  payload: VoluntaryUploadPayload,
): Promise<VoluntaryUploadResponse> => {
  const formData = new FormData();
  formData.append('file', payload.file);
  formData.append('documentTypeCode', payload.documentTypeCode);
  if (payload.customLabel) {
    formData.append('customLabel', payload.customLabel);
  }
  const response = await axiosClient.post<VoluntaryUploadResponse>(
    `/applications/${applicationSeq}/documents`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return response.data;
};

/**
 * 자발적 업로드 삭제
 */
export const deleteDocument = async (
  applicationSeq: number,
  docRequestId: number,
): Promise<void> => {
  await axiosClient.delete(`/applications/${applicationSeq}/documents/${docRequestId}`);
};

// ─────────────────────────────────────────────
// Phase 3 (PR#1 백엔드) — LEW 요청 생성 / 검토
// ─────────────────────────────────────────────

/**
 * LEW/ADMIN이 서류 요청을 배치 생성한다 (AC-R1).
 * 서버는 전체 트랜잭션으로 처리 — 한 건이라도 실패하면 전체 롤백.
 */
export const createDocumentRequests = async (
  applicationSeq: number,
  items: CreateDocumentRequestItem[],
): Promise<CreateDocumentRequestsResponse> => {
  const response = await axiosClient.post<CreateDocumentRequestsResponse>(
    `/admin/applications/${applicationSeq}/document-requests`,
    { items },
  );
  return response.data;
};

/**
 * UPLOADED → APPROVED (AC-S2).
 */
export const approveDocumentRequest = async (
  reqId: number,
): Promise<DocumentRequestDecisionResponse> => {
  const response = await axiosClient.patch<DocumentRequestDecisionResponse>(
    `/admin/document-requests/${reqId}/approve`,
  );
  return response.data;
};

/**
 * UPLOADED → REJECTED (AC-S3). reason은 min 10자.
 */
export const rejectDocumentRequest = async (
  reqId: number,
  rejectionReason: string,
): Promise<DocumentRequestDecisionResponse> => {
  const response = await axiosClient.patch<DocumentRequestDecisionResponse>(
    `/admin/document-requests/${reqId}/reject`,
    { rejectionReason },
  );
  return response.data;
};

/**
 * REQUESTED → CANCELLED (AC-S5). 다른 상태에서는 서버가 409 반환.
 */
export const cancelDocumentRequest = async (
  reqId: number,
): Promise<DocumentRequestDecisionResponse> => {
  const response = await axiosClient.delete<DocumentRequestDecisionResponse>(
    `/admin/document-requests/${reqId}`,
  );
  return response.data;
};

export const documentApi = {
  getDocumentTypes,
  getDocumentRequests,
  uploadVoluntaryDocument,
  deleteDocument,
  createDocumentRequests,
  approveDocumentRequest,
  rejectDocumentRequest,
  cancelDocumentRequest,
};
export default documentApi;
