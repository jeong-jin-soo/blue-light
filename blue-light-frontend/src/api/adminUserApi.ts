import axiosClient from './axiosClient';
import type { ChangeRoleRequest, Page, User } from '../types';

// ── User Management ──────────────────────────────

export const getUsers = async (
  page = 0,
  size = 20,
  role?: string,
  search?: string
): Promise<Page<User>> => {
  const response = await axiosClient.get<Page<User>>('/admin/users', {
    params: { page, size, ...(role && { role }), ...(search && { search }) },
  });
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
