import axiosClient from './axiosClient';
import type { User, UpdateProfileRequest, ChangePasswordRequest } from '../types';

/**
 * Get my profile
 */
export const getMyProfile = async (): Promise<User> => {
  const response = await axiosClient.get<User>('/users/me');
  return response.data;
};

/**
 * Update my profile
 */
export const updateProfile = async (data: UpdateProfileRequest): Promise<User> => {
  const response = await axiosClient.put<User>('/users/me', data);
  return response.data;
};

/**
 * Change password
 */
export const changePassword = async (data: ChangePasswordRequest): Promise<void> => {
  await axiosClient.put('/users/me/password', data);
};

/**
 * Upload or replace profile signature
 */
export const uploadSignature = async (signatureBlob: Blob): Promise<User> => {
  const formData = new FormData();
  formData.append('signature', signatureBlob, 'signature.png');
  const response = await axiosClient.put<User>('/users/me/signature', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return response.data;
};

/**
 * Delete profile signature
 */
export const deleteSignature = async (): Promise<void> => {
  await axiosClient.delete('/users/me/signature');
};

/**
 * Get signature image as data URL (for canvas pre-loading)
 */
export const getSignatureDataUrl = async (): Promise<string | null> => {
  try {
    const response = await axiosClient.get('/users/me/signature', {
      responseType: 'blob',
    });
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result as string);
      reader.readAsDataURL(new Blob([response.data]));
    });
  } catch {
    return null;
  }
};

/**
 * PDPA: Export my data (Right to Access / Data Portability)
 */
export const exportMyData = async (): Promise<Record<string, unknown>> => {
  const response = await axiosClient.get<Record<string, unknown>>('/users/me/data-export');
  return response.data;
};

/**
 * PDPA: Withdraw PDPA consent (Right to Withdrawal)
 */
export const withdrawPdpaConsent = async (): Promise<{ message: string }> => {
  const response = await axiosClient.post<{ message: string }>('/users/me/withdraw-consent');
  return response.data;
};

/**
 * PDPA: Delete my account (Right to Erasure)
 */
export const deleteMyAccount = async (): Promise<void> => {
  await axiosClient.delete('/users/me');
};

export const userApi = {
  getMyProfile, updateProfile, changePassword,
  uploadSignature, deleteSignature, getSignatureDataUrl,
  exportMyData, withdrawPdpaConsent, deleteMyAccount,
};
export default userApi;
