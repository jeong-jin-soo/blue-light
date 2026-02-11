import axiosClient from './axiosClient';
import type { AdminPriceResponse, UpdatePriceRequest } from '../types';

// ── Price Management ──────────────────────────────

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

// ── System Settings ──────────────────────────────

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
