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
  uploadApplicantLoa,
  uploadFinalLoa,
  getActiveLoaForm,
  downloadActiveLoaForm,
};
export default loaApi;
