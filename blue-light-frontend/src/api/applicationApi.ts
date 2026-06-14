import axiosClient from './axiosClient';
import type {
  Application,
  ApplicationSummary,
  CreateApplicationRequest,
  FileInfo,
  Payment,
  SldRequest,
  UpdateApplicationRequest,
} from '../types';

/**
 * Create a new licence application
 */
export const createApplication = async (data: CreateApplicationRequest): Promise<Application> => {
  const response = await axiosClient.post<Application>('/applications', data);
  return response.data;
};

/**
 * Get my applications list
 */
export const getMyApplications = async (): Promise<Application[]> => {
  const response = await axiosClient.get<Application[]>('/applications');
  return response.data;
};

/**
 * Get application detail
 */
export const getApplication = async (id: number): Promise<Application> => {
  const response = await axiosClient.get<Application>(`/applications/${id}`);
  return response.data;
};

/**
 * Update and resubmit application (after revision request)
 */
export const updateApplication = async (id: number, data: UpdateApplicationRequest): Promise<Application> => {
  const response = await axiosClient.put<Application>(`/applications/${id}`, data);
  return response.data;
};

/**
 * Get application summary for dashboard
 */
export const getApplicationSummary = async (): Promise<ApplicationSummary> => {
  const response = await axiosClient.get<ApplicationSummary>('/applications/summary');
  return response.data;
};

/**
 * Get payment history for an application
 */
export const getApplicationPayments = async (applicationId: number): Promise<Payment[]> => {
  const response = await axiosClient.get<Payment[]>(`/applications/${applicationId}/payments`);
  return response.data;
};

/**
 * Get completed applications (갱신 시 원본 선택용)
 */
export const getCompletedApplications = async (): Promise<Application[]> => {
  const response = await axiosClient.get<Application[]>('/applications/completed');
  return response.data;
};

// ============================================
// SLD Request
// ============================================

/**
 * Create SLD request (request LEW to prepare SLD)
 */
export const createSldRequest = async (
  applicationId: number,
  note?: string
): Promise<SldRequest> => {
  const response = await axiosClient.post<SldRequest>(
    `/applications/${applicationId}/sld-request`,
    { note }
  );
  return response.data;
};

/**
 * Get SLD request for an application
 */
export const getSldRequest = async (applicationId: number): Promise<SldRequest | null> => {
  const response = await axiosClient.get<SldRequest>(
    `/applications/${applicationId}/sld-request`
  );
  return response.data;
};

/**
 * Update SLD request (신청자가 메모 + 스케치 파일 업데이트)
 */
export const updateSldRequest = async (
  applicationId: number,
  data: { note?: string; sketchFileSeq?: number | null },
): Promise<SldRequest> => {
  const response = await axiosClient.put<SldRequest>(
    `/applications/${applicationId}/sld-request`,
    data,
  );
  return response.data;
};

/**
 * E2 — 결제 증빙(PAYMENT_RECEIPT) 업로드. ADMIN/SYSTEM_ADMIN 알림(A-55). PENDING_PAYMENT 한정.
 */
export const reportPaymentEvidence = async (
  applicationId: number,
  file: File,
): Promise<FileInfo> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await axiosClient.post<FileInfo>(
    `/applications/${applicationId}/payment/evidence`,
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
  return response.data;
};

/**
 * E3 — 결제 확인 요청(파일 없음). ADMIN/SYSTEM_ADMIN 알림(A-56). PENDING_PAYMENT 한정.
 */
export const requestPaymentConfirmation = async (applicationId: number): Promise<void> => {
  await axiosClient.post(`/applications/${applicationId}/payment/request-confirmation`);
};

export const applicationApi = {
  createApplication,
  updateApplication,
  getMyApplications,
  getApplication,
  getApplicationSummary,
  getApplicationPayments,
  getCompletedApplications,
  createSldRequest,
  getSldRequest,
  updateSldRequest,
  reportPaymentEvidence,
  requestPaymentConfirmation,
};
export default applicationApi;
