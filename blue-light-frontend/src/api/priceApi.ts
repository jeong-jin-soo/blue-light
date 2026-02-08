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
 * Calculate price for a given kVA
 */
export const calculatePrice = async (kva: number): Promise<PriceCalculation> => {
  const response = await axiosClient.get<PriceCalculation>('/prices/calculate', {
    params: { kva },
  });
  return response.data;
};

export const priceApi = { getPrices, calculatePrice };
export default priceApi;
