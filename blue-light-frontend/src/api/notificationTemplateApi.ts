import axiosClient from './axiosClient';
import type { Page } from '../types';
import type {
  CatalogEntry,
  CreateDraftRequest,
  HistoryItem,
  NotificationCategory,
  NotificationChannel,
  NotificationTemplateDetail,
  NotificationTemplateDraft,
  NotificationTemplateListItem,
  TemplateDraftStatus,
  TemplatePreviewResponse,
  TemplateTestSendResponse,
  UpdateDraftRequest,
} from '../types/notificationTemplate';

/**
 * 알림 템플릿 관리 API 클라이언트 (PR-T6).
 *
 * 백엔드: AdminNotificationTemplateController (/api/admin/notification-templates).
 * 권한: NOTIFICATION_MANAGER / SYSTEM_ADMIN (편집), + ADMIN/LEW/CM/SM (read-only).
 *
 * 스펙: doc/Project Analysis/notification-template-manager-spec.md §6.
 */

const BASE = '/admin/notification-templates';

// ─────────────────────────────────────────────────────────────
// 템플릿 조회 / 활성-비활성
// ─────────────────────────────────────────────────────────────
export interface ListTemplatesParams {
  code?: string;
  channel?: NotificationChannel;
  locale?: string;
  enabled?: boolean;
  category?: NotificationCategory;
  role?: string;
  page?: number;
  size?: number;
}

export const listTemplates = async (
  params: ListTemplatesParams = {}
): Promise<Page<NotificationTemplateListItem>> => {
  const response = await axiosClient.get<Page<NotificationTemplateListItem>>(BASE, { params });
  return response.data;
};

export const getTemplate = async (
  templateSeq: number
): Promise<NotificationTemplateDetail> => {
  const response = await axiosClient.get<NotificationTemplateDetail>(`${BASE}/${templateSeq}`);
  return response.data;
};

export const enableTemplate = async (
  templateSeq: number,
  changeReason?: string | null
): Promise<void> => {
  await axiosClient.post(`${BASE}/${templateSeq}/enable`, {
    changeReason: changeReason ?? null,
  });
};

export const disableTemplate = async (
  templateSeq: number,
  changeReason: string
): Promise<void> => {
  await axiosClient.post(`${BASE}/${templateSeq}/disable`, { changeReason });
};

// ─────────────────────────────────────────────────────────────
// Draft CRUD
// ─────────────────────────────────────────────────────────────
export const createDraft = async (
  request: CreateDraftRequest
): Promise<NotificationTemplateDraft> => {
  const response = await axiosClient.post<NotificationTemplateDraft>(`${BASE}/drafts`, request);
  return response.data;
};

export const editDraft = async (
  draftSeq: number,
  request: UpdateDraftRequest
): Promise<NotificationTemplateDraft> => {
  const response = await axiosClient.patch<NotificationTemplateDraft>(
    `${BASE}/drafts/${draftSeq}`,
    request
  );
  return response.data;
};

export const withdrawDraft = async (draftSeq: number): Promise<void> => {
  await axiosClient.post(`${BASE}/drafts/${draftSeq}/withdraw`);
};

export const getDraft = async (draftSeq: number): Promise<NotificationTemplateDraft> => {
  const response = await axiosClient.get<NotificationTemplateDraft>(`${BASE}/drafts/${draftSeq}`);
  return response.data;
};

export interface ListDraftsParams {
  status?: TemplateDraftStatus;
  myOnly?: boolean;
  page?: number;
  size?: number;
}

export const listDrafts = async (
  params: ListDraftsParams = {}
): Promise<Page<NotificationTemplateDraft>> => {
  const response = await axiosClient.get<Page<NotificationTemplateDraft>>(`${BASE}/drafts`, {
    params,
  });
  return response.data;
};

// ─────────────────────────────────────────────────────────────
// 2-step publish (SA only)
// ─────────────────────────────────────────────────────────────
export const approveDraft = async (
  draftSeq: number,
  reviewNote?: string | null
): Promise<NotificationTemplateDetail> => {
  const response = await axiosClient.post<NotificationTemplateDetail>(
    `${BASE}/drafts/${draftSeq}/approve`,
    { reviewNote: reviewNote ?? null }
  );
  return response.data;
};

export const rejectDraft = async (draftSeq: number, reviewNote: string): Promise<void> => {
  await axiosClient.post(`${BASE}/drafts/${draftSeq}/reject`, { reviewNote });
};

// ─────────────────────────────────────────────────────────────
// Preview + Test-send
// ─────────────────────────────────────────────────────────────
export const previewTemplate = async (
  templateSeq: number,
  payload: Record<string, string>
): Promise<TemplatePreviewResponse> => {
  const response = await axiosClient.post<TemplatePreviewResponse>(
    `${BASE}/${templateSeq}/preview`,
    { payload }
  );
  return response.data;
};

export const testSendTemplate = async (
  templateSeq: number,
  payload: Record<string, string>
): Promise<TemplateTestSendResponse> => {
  const response = await axiosClient.post<TemplateTestSendResponse>(
    `${BASE}/${templateSeq}/test-send`,
    { payload }
  );
  return response.data;
};

// ─────────────────────────────────────────────────────────────
// Catalog + History
// ─────────────────────────────────────────────────────────────
export const listCatalog = async (): Promise<CatalogEntry[]> => {
  const response = await axiosClient.get<CatalogEntry[]>(`${BASE}/catalog`);
  return response.data;
};

export const getCatalog = async (templateCode: string): Promise<CatalogEntry> => {
  const response = await axiosClient.get<CatalogEntry>(`${BASE}/catalog/${templateCode}`);
  return response.data;
};

export const getHistory = async (
  templateSeq: number,
  page = 0,
  size = 30
): Promise<Page<HistoryItem>> => {
  const response = await axiosClient.get<Page<HistoryItem>>(`${BASE}/${templateSeq}/history`, {
    params: { page, size },
  });
  return response.data;
};
