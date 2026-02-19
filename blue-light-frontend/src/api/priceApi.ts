import axiosClient from './axiosClient';
import type { MasterPrice, PriceCalculation } from '../types';

/**
 * Get all active price tiers
 */
export const getPrices = async (): Promise<MasterPrice[]> => {
  const response = await axiosClient.get<MasterPrice[]>('/prices');
  return response.data;
};

/**
 * Calculate price for a given kVA (with optional SLD option and licence period)
 */
export const calculatePrice = async (kva: number, months?: number, sldOption?: string): Promise<PriceCalculation> => {
  const params: Record<string, string | number> = { kva };
  if (months) params.months = months;
  if (sldOption) params.sldOption = sldOption;
  const response = await axiosClient.get<PriceCalculation>('/prices/calculate', { params });
  return response.data;
};

/**
 * Get payment receiving info (public, no auth)
 */
export const getPaymentInfo = async (): Promise<Record<string, string>> => {
  const response = await axiosClient.get<Record<string, string>>('/public/payment-info');
  return response.data;
};

export const priceApi = { getPrices, calculatePrice, getPaymentInfo };
export default priceApi;
