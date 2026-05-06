import api from './axiosClient';
import type {
  LightingOrder,
  LightingOrderDashboard,
  LightingOrderPayment,
  Page,
  ProposeQuoteRequest,
} from '../types';

/**
 * Lighting Manager API — SLD_MANAGER / ADMIN / SYSTEM_ADMIN 전용.
 * Endpoints are `/api/lighting-manager/**` and mirror the SLD Manager API.
 */
export const lightingManagerApi = {
  getDashboard: () =>
    api.get<LightingOrderDashboard>('/lighting-manager/dashboard').then(r => r.data),

  getOrders: (params?: { status?: string; page?: number; size?: number }) =>
    api.get<Page<LightingOrder>>('/lighting-manager/orders', { params }).then(r => r.data),

  getOrder: (id: number) =>
    api.get<LightingOrder>(`/lighting-manager/orders/${id}`).then(r => r.data),

  proposeQuote: (id: number, data: ProposeQuoteRequest) =>
    api.post<LightingOrder>(`/lighting-manager/orders/${id}/propose-quote`, data).then(r => r.data),

  assignManager: (id: number, managerUserSeq: number) =>
    api.post<LightingOrder>(`/lighting-manager/orders/${id}/assign`, { managerUserSeq }).then(r => r.data),

  unassignManager: (id: number) =>
    api.delete<LightingOrder>(`/lighting-manager/orders/${id}/assign`).then(r => r.data),

  uploadDeliverableComplete: (id: number, fileSeq: number, managerNote?: string) =>
    api.post<LightingOrder>(`/lighting-manager/orders/${id}/sld-uploaded`, { fileSeq, managerNote }).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<LightingOrder>(`/lighting-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<LightingOrder>(`/lighting-manager/orders/${id}/complete`).then(r => r.data),

  uploadFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/lighting-manager/orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  getPayments: (id: number) =>
    api.get<LightingOrderPayment[]>(`/lighting-manager/orders/${id}/payments`).then(r => r.data),
};
