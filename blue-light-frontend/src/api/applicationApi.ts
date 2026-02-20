import axiosClient from './axiosClient';
import type {
  Application,
  ApplicationSummary,
  CreateApplicationRequest,
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
};
export default applicationApi;
