import { create } from 'zustand';
import notificationApi from '../api/notificationApi';

interface NotificationState {
  unreadCount: number;
  fetchUnreadCount: () => Promise<void>;
  decrementUnreadCount: () => void;
  clearUnreadCount: () => void;
}

export const useNotificationStore = create<NotificationState>((set) => ({
  unreadCount: 0,

  fetchUnreadCount: async () => {
    try {
      const count = await notificationApi.getUnreadCount();
      set({ unreadCount: count });
    } catch {
      // silent — user may not be authenticated
    }
  },

  decrementUnreadCount: () =>
    set((s) => ({ unreadCount: Math.max(0, s.unreadCount - 1) })),

  clearUnreadCount: () => set({ unreadCount: 0 }),
}));
