import axiosClient from './axiosClient';
import type { AppNotification, Page } from '../types';

const notificationApi = {
  getNotifications: async (page = 0, size = 20): Promise<Page<AppNotification>> => {
    const response = await axiosClient.get<Page<AppNotification>>('/notifications', { params: { page, size } });
    return response.data;
  },

  getUnreadCount: async (): Promise<number> => {
    const response = await axiosClient.get<{ count: number }>('/notifications/unread-count');
    return response.data.count;
  },

  markAsRead: async (notificationSeq: number): Promise<void> => {
    await axiosClient.patch(`/notifications/${notificationSeq}/read`);
  },

  markAllAsRead: async (): Promise<void> => {
    await axiosClient.patch('/notifications/read-all');
  },
};

export default notificationApi;
