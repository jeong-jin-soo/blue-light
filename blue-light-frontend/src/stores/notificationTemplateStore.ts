import { create } from 'zustand';
import type { Page } from '../types';
import type {
  CatalogEntry,
  NotificationTemplateDetail,
  NotificationTemplateDraft,
  NotificationTemplateListItem,
  TemplateDraftStatus,
} from '../types/notificationTemplate';
import * as api from '../api/notificationTemplateApi';

interface ListFilters {
  code?: string;
  channel?: api.ListTemplatesParams['channel'];
  locale?: string;
  enabled?: boolean;
  category?: api.ListTemplatesParams['category'];
  page: number;
  size: number;
}

interface NotificationTemplateState {
  // List
  templates: NotificationTemplateListItem[];
  templatesPage: Page<NotificationTemplateListItem> | null;
  templatesLoading: boolean;
  templatesError: string | null;
  filters: ListFilters;

  // Detail
  current: NotificationTemplateDetail | null;
  currentLoading: boolean;
  currentError: string | null;

  // Draft queue (SA)
  draftQueue: NotificationTemplateDraft[];
  draftQueueLoading: boolean;
  draftQueueError: string | null;
  draftStatusFilter: TemplateDraftStatus;

  // Catalog
  catalog: CatalogEntry[];
  catalogLoaded: boolean;

  // Actions
  setFilters: (next: Partial<ListFilters>) => void;
  loadTemplates: () => Promise<void>;
  loadTemplate: (templateSeq: number) => Promise<void>;
  loadDraftQueue: (status?: TemplateDraftStatus, myOnly?: boolean) => Promise<void>;
  loadCatalog: () => Promise<void>;
  clearCurrent: () => void;
}

const DEFAULT_FILTERS: ListFilters = { page: 0, size: 50 };

export const useNotificationTemplateStore = create<NotificationTemplateState>((set, get) => ({
  templates: [],
  templatesPage: null,
  templatesLoading: false,
  templatesError: null,
  filters: DEFAULT_FILTERS,

  current: null,
  currentLoading: false,
  currentError: null,

  draftQueue: [],
  draftQueueLoading: false,
  draftQueueError: null,
  draftStatusFilter: 'PENDING',

  catalog: [],
  catalogLoaded: false,

  setFilters: (next) =>
    set((state) => ({
      filters: { ...state.filters, ...next, page: next.page ?? 0 },
    })),

  loadTemplates: async () => {
    set({ templatesLoading: true, templatesError: null });
    try {
      const { code, channel, locale, enabled, category, page, size } = get().filters;
      const data = await api.listTemplates({
        code: code || undefined,
        channel: channel || undefined,
        locale: locale || undefined,
        enabled,
        category: category || undefined,
        page,
        size,
      });
      set({ templates: data.content, templatesPage: data, templatesLoading: false });
    } catch (e) {
      set({
        templatesLoading: false,
        templatesError: e instanceof Error ? e.message : 'Failed to load templates',
      });
    }
  },

  loadTemplate: async (templateSeq) => {
    set({ currentLoading: true, currentError: null });
    try {
      const data = await api.getTemplate(templateSeq);
      set({ current: data, currentLoading: false });
    } catch (e) {
      set({
        currentLoading: false,
        currentError: e instanceof Error ? e.message : 'Failed to load template',
      });
    }
  },

  loadDraftQueue: async (status = 'PENDING', myOnly = false) => {
    set({ draftQueueLoading: true, draftQueueError: null, draftStatusFilter: status });
    try {
      const data = await api.listDrafts({ status, myOnly, page: 0, size: 50 });
      set({ draftQueue: data.content, draftQueueLoading: false });
    } catch (e) {
      set({
        draftQueueLoading: false,
        draftQueueError: e instanceof Error ? e.message : 'Failed to load drafts',
      });
    }
  },

  loadCatalog: async () => {
    if (get().catalogLoaded) return;
    try {
      const data = await api.listCatalog();
      set({ catalog: data, catalogLoaded: true });
    } catch {
      // 카탈로그 미시드 환경에서도 페이지는 동작해야 함
      set({ catalog: [], catalogLoaded: true });
    }
  },

  clearCurrent: () => set({ current: null, currentError: null }),
}));
