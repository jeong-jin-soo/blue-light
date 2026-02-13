import axiosClient from './axiosClient';
import type { AdminPriceResponse, UpdatePriceRequest, BatchUpdatePricesRequest } from '../types';

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

export const batchUpdatePrices = async (
  data: BatchUpdatePricesRequest
): Promise<AdminPriceResponse[]> => {
  const response = await axiosClient.put<AdminPriceResponse[]>('/admin/prices/batch', data);
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

// ── PayNow QR Image ──────────────────────────────

export const uploadPaymentQr = async (
  file: File
): Promise<{ filePath: string; url: string }> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await axiosClient.post<{ filePath: string; url: string }>(
    '/admin/settings/payment-qr',
    formData,
    { headers: { 'Content-Type': 'multipart/form-data' } }
  );
  return response.data;
};

export const deletePaymentQr = async (): Promise<void> => {
  await axiosClient.delete('/admin/settings/payment-qr');
};
