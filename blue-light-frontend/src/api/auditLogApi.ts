import axiosClient from './axiosClient';
import type { AuditLog, AuditCategory, AuditAction, Page } from '../types';

export interface AuditLogFilter {
  category?: AuditCategory;
  action?: AuditAction;
  userSeq?: number;
  entityType?: string;
  entityId?: string;
  startDate?: string;
  endDate?: string;
  search?: string;
  page?: number;
  size?: number;
}

export const getAuditLogs = async (filter: AuditLogFilter = {}): Promise<Page<AuditLog>> => {
  const params: Record<string, string | number> = {};
  if (filter.category) params.category = filter.category;
  if (filter.action) params.action = filter.action;
  if (filter.userSeq) params.userSeq = filter.userSeq;
  if (filter.entityType) params.entityType = filter.entityType;
  if (filter.entityId) params.entityId = filter.entityId;
  if (filter.startDate) params.startDate = filter.startDate;
  if (filter.endDate) params.endDate = filter.endDate;
  if (filter.search) params.search = filter.search;
  params.page = filter.page ?? 0;
  params.size = filter.size ?? 20;

  const response = await axiosClient.get<Page<AuditLog>>('/admin/audit-logs', { params });
  return response.data;
};
