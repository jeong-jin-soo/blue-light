import axiosClient from './axiosClient';
import type {
  AdminApplication,
  AdminDashboard,
  AdminPriceResponse,
  ApplicationStatus,
  AssignLewRequest,
  ChangeRoleRequest,
  CompleteApplicationRequest,
  FileInfo,
  FileType,
  LewSummary,
  Page,
  Payment,
  PaymentConfirmRequest,
  RevisionRequest,
  UpdatePriceRequest,
  UpdateStatusRequest,
  User,
} from '../types';

// ============================================
// Dashboard
// ============================================

export const getDashboard = async (): Promise<AdminDashboard> => {
  const response = await axiosClient.get<AdminDashboard>('/admin/dashboard');
  return response.data;
};

// ============================================
// Applications
// ============================================

export const getApplications = async (
  page = 0,
  size = 20,
  status?: ApplicationStatus,
  search?: string
): Promise<Page<AdminApplication>> => {
  const response = await axiosClient.get<Page<AdminApplication>>('/admin/applications', {
    params: { page, size, ...(status && { status }), ...(search && { search }) },
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

// ============================================
// Payments
// ============================================

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

// ============================================
// Files (admin)
// ============================================

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

// ============================================
// LEW Assignment
// ============================================

export const getAvailableLews = async (): Promise<LewSummary[]> => {
  const response = await axiosClient.get<LewSummary[]>('/admin/lews');
  return response.data;
};

export const assignLew = async (
  applicationId: number,
  data: AssignLewRequest
): Promise<AdminApplication> => {
  const response = await axiosClient.post<AdminApplication>(
    `/admin/applications/${applicationId}/assign-lew`,
    data
  );
  return response.data;
};

export const unassignLew = async (applicationId: number): Promise<AdminApplication> => {
  const response = await axiosClient.delete<AdminApplication>(
    `/admin/applications/${applicationId}/assign-lew`
  );
  return response.data;
};

// ============================================
// Price Management
// ============================================

export const getPrices = async (): Promise<AdminPriceResponse[]> => {
  const response = await axiosClient.get<AdminPriceResponse[]>('/admin/prices');
  return response.data;
};

export const updatePrice = async (
  id: number,
  data: UpdatePriceRequest
): Promise<AdminPriceResponse> => {
  const response = await axiosClient.put<AdminPriceResponse>(`/admin/prices/${id}`, data);
  return response.data;
};

// ============================================
// System Settings
// ============================================

export const getSettings = async (): Promise<Record<string, string>> => {
  const response = await axiosClient.get<Record<string, string>>('/admin/settings');
  return response.data;
};

export const updateSettings = async (
  data: Record<string, string>
): Promise<Record<string, string>> => {
  const response = await axiosClient.patch<Record<string, string>>('/admin/settings', data);
  return response.data;
};

// ============================================
// Users
// ============================================

export const getUsers = async (): Promise<User[]> => {
  const response = await axiosClient.get<User[]>('/admin/users');
  return response.data;
};

export const changeUserRole = async (id: number, data: ChangeRoleRequest): Promise<User> => {
  const response = await axiosClient.patch<User>(`/admin/users/${id}/role`, data);
  return response.data;
};

export const approveLew = async (id: number): Promise<User> => {
  const response = await axiosClient.post<User>(`/admin/users/${id}/approve`);
  return response.data;
};

export const rejectLew = async (id: number): Promise<User> => {
  const response = await axiosClient.post<User>(`/admin/users/${id}/reject`);
  return response.data;
};

export const adminApi = {
  getDashboard,
  getApplications,
  getApplication,
  updateStatus,
  requestRevision,
  approveForPayment,
  completeApplication,
  confirmPayment,
  getPayments,
  uploadFile,
  getAvailableLews,
  assignLew,
  unassignLew,
  getPrices,
  updatePrice,
  getSettings,
  updateSettings,
  getUsers,
  changeUserRole,
  approveLew,
  rejectLew,
};
export default adminApi;
