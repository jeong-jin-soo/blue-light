import axiosClient from './axiosClient';
import type { FileInfo, LoaActiveForm, LoaStatus } from '../types';

/**
 * LOA PDF 생성 (Admin/LEW 액션)
 */
export const generateLoa = async (applicationId: number): Promise<FileInfo> => {
  const response = await axiosClient.post<FileInfo>(
    `/admin/applications/${applicationId}/loa/generate`
  );
  return response.data;
};

/**
 * LOA 전자서명 (Applicant 액션)
 * @param signatureBlob - 서명 캔버스에서 추출한 PNG Blob
 */
export const signLoa = async (applicationId: number, signatureBlob: Blob): Promise<FileInfo> => {
  const formData = new FormData();
  formData.append('signature', signatureBlob, 'signature.png');

  const response = await axiosClient.post<FileInfo>(
    `/applications/${applicationId}/loa/sign`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

/**
 * LOA 상태 조회
 */
export const getLoaStatus = async (applicationId: number): Promise<LoaStatus> => {
  const response = await axiosClient.get<LoaStatus>(
    `/applications/${applicationId}/loa/status`
  );
  return response.data;
};

// ── Kaki Concierge v1.5 Phase 1 PR#6 Stage B: Manager 대리 서명 업로드 ──

export interface LoaUploadByManagerPayload {
  /** PNG/JPEG 서명 이미지 (최대 2MB) */
  signature: Blob;
  /** Manager 수령 경로 메모 (선택) */
  memo?: string;
  /**
   * Manager가 신청자로부터 직접 수령했음을 명시적 확인.
   * UI 체크박스와 동기화되며 false면 backend에서 400 ACKNOWLEDGEMENT_REQUIRED.
   */
  acknowledgeReceipt: true;
}

/**
 * Manager 대리 서명 업로드 (경로 A).
 * - Backend: POST /api/admin/applications/{id}/loa/upload-signature
 * - 권한: CONCIERGE_MANAGER (본인 담당 + viaConcierge) / ADMIN / SYSTEM_ADMIN
 * - 성공 시 연결된 ConciergeRequest가 AWAITING_APPLICANT_LOA_SIGN → AWAITING_LICENCE_PAYMENT 자동 전이
 *   + 신청자에게 N5-UploadConfirm 이메일 (7일 이의 제기 창구) 발송
 */
export const uploadLoaSignatureByManager = async (
  applicationId: number,
  payload: LoaUploadByManagerPayload
): Promise<FileInfo> => {
  const form = new FormData();
  form.append('signature', payload.signature, 'signature.png');
  if (payload.memo && payload.memo.trim()) {
    form.append('memo', payload.memo.trim());
  }
  form.append('acknowledgeReceipt', String(payload.acknowledgeReceipt));
  const response = await axiosClient.post<FileInfo>(
    `/admin/applications/${applicationId}/loa/upload-signature`,
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } }
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
  generateLoa,
  signLoa,
  getLoaStatus,
  uploadLoaSignatureByManager,
  // 교환 모델 (PR3b)
  sendLoaForm,
  uploadApplicantLoa,
  uploadFinalLoa,
  getActiveLoaForm,
  downloadActiveLoaForm,
};
export default loaApi;
