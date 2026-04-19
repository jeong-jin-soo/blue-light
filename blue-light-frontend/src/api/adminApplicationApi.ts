import axiosClient from './axiosClient';
import type {
  AdminApplication,
  AdminDashboard,
  ApplicationStatus,
  CompleteApplicationRequest,
  FileInfo,
  FileType,
  KvaStatus,
  KvaSource,
  Page,
  Payment,
  PaymentConfirmRequest,
  RevisionRequest,
  SldRequest,
  UpdateStatusRequest,
} from '../types';

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
  kvaStatus?: KvaStatus
): Promise<Page<AdminApplication>> => {
  const response = await axiosClient.get<Page<AdminApplication>>('/admin/applications', {
    params: {
      page,
      size,
      ...(status && { status }),
      ...(search && { search }),
      ...(kvaStatus && { kvaStatus }),
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
