import api from './axiosClient';
import type { SldOrder, SldOrderDashboard, SldOrderPayment, Page, ProposeQuoteRequest } from '../types';

export const sldManagerApi = {
  getDashboard: () =>
    api.get<SldOrderDashboard>('/sld-manager/dashboard').then(r => r.data),

  getOrders: (params?: { status?: string; page?: number; size?: number }) =>
    api.get<Page<SldOrder>>('/sld-manager/orders', { params }).then(r => r.data),

  getOrder: (id: number) =>
    api.get<SldOrder>(`/sld-manager/orders/${id}`).then(r => r.data),

  proposeQuote: (id: number, data: ProposeQuoteRequest) =>
    api.post<SldOrder>(`/sld-manager/orders/${id}/propose-quote`, data).then(r => r.data),

  assignManager: (id: number, managerUserSeq: number) =>
    api.post<SldOrder>(`/sld-manager/orders/${id}/assign`, { managerUserSeq }).then(r => r.data),

  unassignManager: (id: number) =>
    api.delete<SldOrder>(`/sld-manager/orders/${id}/assign`).then(r => r.data),

  uploadSldComplete: (id: number, fileSeq: number, managerNote?: string) =>
    api.post<SldOrder>(`/sld-manager/orders/${id}/sld-uploaded`, { fileSeq, managerNote }).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<SldOrder>(`/sld-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<SldOrder>(`/sld-manager/orders/${id}/complete`).then(r => r.data),

  uploadFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/sld-manager/orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  getPayments: (id: number) =>
    api.get<SldOrderPayment[]>(`/sld-manager/orders/${id}/payments`).then(r => r.data),
};
