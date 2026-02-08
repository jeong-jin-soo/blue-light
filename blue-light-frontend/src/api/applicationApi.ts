import axiosClient from './axiosClient';
import type {
  Application,
  ApplicationSummary,
  CreateApplicationRequest,
  Payment,
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

export const applicationApi = {
  createApplication,
  getMyApplications,
  getApplication,
  getApplicationSummary,
  getApplicationPayments,
};
export default applicationApi;
