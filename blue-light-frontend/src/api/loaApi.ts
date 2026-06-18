import axiosClient from './axiosClient';
import type { LoaActiveForm, LoaStatus } from '../types';

/**
 * LOA 상태 조회
 */
export const getLoaStatus = async (applicationId: number): Promise<LoaStatus> => {
  const response = await axiosClient.get<LoaStatus>(
    `/applications/${applicationId}/loa/status`
  );
  return response.data;
};

// ══════════════════════════════════════════════════════════════════
//  교환 모델 (loa-exchange-redesign-spec.md §3.2 / §3.3, PR3b)
// ══════════════════════════════════════════════════════════════════

/**
 * LEW: active LoA 폼을 신청자에게 전달 (NEW 전용).
 * POST /api/lew/applications/{id}/loa/send-form
 */
export const sendLoaForm = async (applicationId: number): Promise<LoaStatus> => {
  const response = await axiosClient.post<LoaStatus>(
    `/lew/applications/${applicationId}/loa/send-form`
  );
  return response.data;
};

/**
 * ADMIN/SYSTEM_ADMIN: 신청자에게 active LoA 폼 전달 (LEW send-form 의 admin 경로).
 * POST /api/admin/applications/{id}/loa/send-form
 */
export const adminSendLoaForm = async (applicationId: number): Promise<LoaStatus> => {
  const response = await axiosClient.post<LoaStatus>(
    `/admin/applications/${applicationId}/loa/send-form`
  );
  return response.data;
};

/**
 * 신청자(또는 ADMIN 대리): 오프라인 서명본 업로드.
 * POST /api/applications/{id}/loa/applicant-upload
 */
export const uploadApplicantLoa = async (
  applicationId: number,
  file: File
): Promise<LoaStatus> => {
  const form = new FormData();
  form.append('file', file);
  const response = await axiosClient.post<LoaStatus>(
    `/applications/${applicationId}/loa/applicant-upload`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * LEW: 보완한 최종본 업로드.
 * POST /api/lew/applications/{id}/loa/final-upload
 */
export const uploadFinalLoa = async (
  applicationId: number,
  file: File
): Promise<LoaStatus> => {
  const form = new FormData();
  form.append('file', file);
  const response = await axiosClient.post<LoaStatus>(
    `/lew/applications/${applicationId}/loa/final-upload`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * ADMIN/SYSTEM_ADMIN: LoA 파일 등록/교체 (Part B 교환 패널).
 * POST /api/admin/applications/{id}/loa/admin-replace
 *
 * 기존 동일 타입 파일은 서버에서 보관(삭제 안 함)되며, 사유(reason)는 필수로 감사에 기록된다.
 * @param fileType OWNER_AUTH_LETTER(신청자 서명본) 또는 LOA_FINAL(LEW 최종본)
 */
export const adminReplaceLoa = async (
  applicationId: number,
  fileType: 'OWNER_AUTH_LETTER' | 'LOA_FINAL',
  file: File,
  reason: string
): Promise<LoaStatus> => {
  const form = new FormData();
  form.append('file', file);
  form.append('fileType', fileType);
  form.append('reason', reason);
  const response = await axiosClient.post<LoaStatus>(
    `/admin/applications/${applicationId}/loa/admin-replace`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * active LoA 폼 메타 조회 (NEW 전용).
 * GET /api/applications/{id}/loa/active-form
 * RENEWAL 또는 active 폼 부재 시 404.
 */
export const getActiveLoaForm = async (applicationId: number): Promise<LoaActiveForm> => {
  const response = await axiosClient.get<LoaActiveForm>(
    `/applications/${applicationId}/loa/active-form`
  );
  return response.data;
};

/**
 * active LoA 폼 PDF 다운로드 (blob).
 * GET /api/applications/{id}/loa/active-form/download
 */
export const downloadActiveLoaForm = async (applicationId: number): Promise<Blob> => {
  const response = await axiosClient.get(
    `/applications/${applicationId}/loa/active-form/download`,
    { responseType: 'blob' }
  );
  return response.data as Blob;
};

export const loaApi = {
  getLoaStatus,
  // 교환 모델 (PR3b)
  sendLoaForm,
  adminSendLoaForm,
  uploadApplicantLoa,
  uploadFinalLoa,
  getActiveLoaForm,
  downloadActiveLoaForm,
  // Part B — admin 교환 패널
  adminReplaceLoa,
};
export default loaApi;
