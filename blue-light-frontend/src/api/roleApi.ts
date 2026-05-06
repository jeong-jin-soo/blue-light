import axiosClient from './axiosClient';
import type { UserRole } from '../constants/roles';

export interface RoleMetadata {
  roleCode: UserRole;
  displayLabel: string;
  assignable: boolean;
  filterable: boolean;
  sortOrder: number;
}

export interface UpdateRoleMetadataRequest {
  displayLabel?: string;
  assignable?: boolean;
  filterable?: boolean;
  sortOrder?: number;
}

// 인증된 모든 사용자 — 프론트 부팅 시 1회 호출
export const getRoles = async (): Promise<RoleMetadata[]> => {
  const response = await axiosClient.get<RoleMetadata[]>('/roles');
  return response.data;
};

// SYSTEM_ADMIN 전용
export const getRolesAdmin = async (): Promise<RoleMetadata[]> => {
  const response = await axiosClient.get<RoleMetadata[]>('/admin/roles');
  return response.data;
};

export const updateRoleMetadata = async (
  roleCode: UserRole,
  request: UpdateRoleMetadataRequest,
): Promise<RoleMetadata> => {
  const response = await axiosClient.patch<RoleMetadata>(`/admin/roles/${roleCode}`, request);
  return response.data;
};
