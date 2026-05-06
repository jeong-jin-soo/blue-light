import api from './axiosClient';
import type {
  CheckOutRequest,
  ExpiredLicenseOrder,
  ExpiredLicenseOrderDashboard,
  ExpiredLicenseOrderPayment,
  Page,
  ProposeQuoteRequest,
  ScheduleVisitRequest,
} from '../types';

/**
 * Expired License Manager API — SLD_MANAGER / ADMIN / SYSTEM_ADMIN 전용.
 * Endpoints are `/api/expired-license-manager/**`.
 */
export const expiredLicenseManagerApi = {
  getDashboard: () =>
    api.get<ExpiredLicenseOrderDashboard>('/expired-license-manager/dashboard').then(r => r.data),

  getOrders: (params?: { status?: string; page?: number; size?: number }) =>
    api.get<Page<ExpiredLicenseOrder>>('/expired-license-manager/orders', { params }).then(r => r.data),

  getOrder: (id: number) =>
    api.get<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}`).then(r => r.data),

  proposeQuote: (id: number, data: ProposeQuoteRequest) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}/propose-quote`, data).then(r => r.data),

  assignManager: (id: number, managerUserSeq: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}/assign`, { managerUserSeq }).then(r => r.data),

  unassignManager: (id: number) =>
    api.delete<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}/assign`).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${id}/complete`).then(r => r.data),

  scheduleVisit: (orderId: number, payload: ScheduleVisitRequest) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${orderId}/schedule-visit`, payload).then(r => r.data),

  checkIn: (orderId: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${orderId}/check-in`).then(r => r.data),

  checkOut: (orderId: number, payload: CheckOutRequest) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-manager/orders/${orderId}/check-out`, payload).then(r => r.data),

  uploadVisitPhotos: (orderId: number, files: File[], captions?: (string | undefined)[]) => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    if (captions) {
      captions.forEach((c) => formData.append('captions', c ?? ''));
    }
    return api.post<ExpiredLicenseOrder>(
      `/expired-license-manager/orders/${orderId}/visit-photos`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    ).then(r => r.data);
  },

  uploadFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/expired-license-manager/orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  getPayments: (id: number) =>
    api.get<ExpiredLicenseOrderPayment[]>(`/expired-license-manager/orders/${id}/payments`).then(r => r.data),
};
