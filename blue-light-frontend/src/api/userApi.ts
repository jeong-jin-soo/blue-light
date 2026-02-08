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

export const userApi = { getMyProfile, updateProfile, changePassword };
export default userApi;
