import api from './axiosClient';
import type {
  PowerSocketOrder,
  PowerSocketOrderDashboard,
  PowerSocketOrderPayment,
  Page,
  ProposeQuoteRequest,
} from '../types';

/**
 * Power Socket Manager API — SLD_MANAGER / ADMIN / SYSTEM_ADMIN 전용.
 * Endpoints are `/api/power-socket-manager/**` and mirror the SLD Manager API.
 */
export const powerSocketManagerApi = {
  getDashboard: () =>
    api.get<PowerSocketOrderDashboard>('/power-socket-manager/dashboard').then(r => r.data),

  getOrders: (params?: { status?: string; page?: number; size?: number }) =>
    api.get<Page<PowerSocketOrder>>('/power-socket-manager/orders', { params }).then(r => r.data),

  getOrder: (id: number) =>
    api.get<PowerSocketOrder>(`/power-socket-manager/orders/${id}`).then(r => r.data),

  proposeQuote: (id: number, data: ProposeQuoteRequest) =>
    api.post<PowerSocketOrder>(`/power-socket-manager/orders/${id}/propose-quote`, data).then(r => r.data),

  assignManager: (id: number, managerUserSeq: number) =>
    api.post<PowerSocketOrder>(`/power-socket-manager/orders/${id}/assign`, { managerUserSeq }).then(r => r.data),

  unassignManager: (id: number) =>
    api.delete<PowerSocketOrder>(`/power-socket-manager/orders/${id}/assign`).then(r => r.data),

  uploadDeliverableComplete: (id: number, fileSeq: number, managerNote?: string) =>
    api.post<PowerSocketOrder>(`/power-socket-manager/orders/${id}/sld-uploaded`, { fileSeq, managerNote }).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<PowerSocketOrder>(`/power-socket-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<PowerSocketOrder>(`/power-socket-manager/orders/${id}/complete`).then(r => r.data),

  uploadFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/power-socket-manager/orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  getPayments: (id: number) =>
    api.get<PowerSocketOrderPayment[]>(`/power-socket-manager/orders/${id}/payments`).then(r => r.data),
};
