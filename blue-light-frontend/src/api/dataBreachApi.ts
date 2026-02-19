import axiosClient from './axiosClient';
import type { Page } from '../types';

export interface DataBreach {
  breachSeq: number;
  title: string;
  description: string;
  severity: string;
  status: string;
  affectedCount: number;
  dataTypesAffected?: string;
  containmentActions?: string;
  pdpcNotifiedAt?: string;
  pdpcReferenceNo?: string;
  usersNotifiedAt?: string;
  resolvedAt?: string;
  reportedBy?: number;
  pdpcOverdue: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DataBreachRequest {
  title: string;
  description: string;
  severity: string;
  affectedCount?: number;
  dataTypesAffected?: string;
  containmentActions?: string;
}

export const getDataBreaches = async (
  status?: string,
  page = 0,
  size = 20
): Promise<Page<DataBreach>> => {
  const params: Record<string, string | number> = { page, size };
  if (status) params.status = status;
  const response = await axiosClient.get<Page<DataBreach>>('/admin/data-breaches', { params });
  return response.data;
};

export const getDataBreach = async (breachSeq: number): Promise<DataBreach> => {
  const response = await axiosClient.get<DataBreach>(`/admin/data-breaches/${breachSeq}`);
  return response.data;
};

export const reportDataBreach = async (data: DataBreachRequest): Promise<DataBreach> => {
  const response = await axiosClient.post<DataBreach>('/admin/data-breaches', data);
  return response.data;
};

export const notifyPdpc = async (breachSeq: number, pdpcReferenceNo: string): Promise<DataBreach> => {
  const response = await axiosClient.put<DataBreach>(
    `/admin/data-breaches/${breachSeq}/pdpc-notify`,
    { pdpcReferenceNo }
  );
  return response.data;
};

export const notifyUsers = async (breachSeq: number): Promise<DataBreach> => {
  const response = await axiosClient.put<DataBreach>(
    `/admin/data-breaches/${breachSeq}/users-notify`
  );
  return response.data;
};

export const resolveBreach = async (breachSeq: number): Promise<DataBreach> => {
  const response = await axiosClient.put<DataBreach>(
    `/admin/data-breaches/${breachSeq}/resolve`
  );
  return response.data;
};
