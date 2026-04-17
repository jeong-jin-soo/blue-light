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

export const documentApi = {
  getDocumentTypes,
  getDocumentRequests,
  uploadVoluntaryDocument,
  deleteDocument,
};
export default documentApi;
