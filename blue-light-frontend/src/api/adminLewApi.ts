import axiosClient from './axiosClient';
import type { AdminApplication, AssignLewRequest, LewSummary } from '../types';

// ── LEW Assignment ──────────────────────────────

export const getAvailableLews = async (kva?: number): Promise<LewSummary[]> => {
  const response = await axiosClient.get<LewSummary[]>('/admin/lews', {
    params: kva ? { kva } : undefined,
  });
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
