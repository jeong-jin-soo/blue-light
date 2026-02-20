import api from './axiosClient';
import type { SldOrder, CreateSldOrderRequest, SldOrderPayment } from '../types';

export const sldOrderApi = {
  createSldOrder: (data: CreateSldOrderRequest) =>
    api.post<SldOrder>('/sld-orders', data).then(r => r.data),

  getMySldOrders: () =>
    api.get<SldOrder[]>('/sld-orders').then(r => r.data),

  getSldOrder: (id: number) =>
    api.get<SldOrder>(`/sld-orders/${id}`).then(r => r.data),

  updateSldOrder: (id: number, data: { applicantNote?: string; sketchFileSeq?: number }) =>
    api.put<SldOrder>(`/sld-orders/${id}`, data).then(r => r.data),

  acceptQuote: (id: number) =>
    api.post<SldOrder>(`/sld-orders/${id}/accept-quote`).then(r => r.data),

  rejectQuote: (id: number) =>
    api.post<SldOrder>(`/sld-orders/${id}/reject-quote`).then(r => r.data),

  requestRevision: (id: number, comment: string) =>
    api.post<SldOrder>(`/sld-orders/${id}/request-revision`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<SldOrder>(`/sld-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<SldOrderPayment[]>(`/sld-orders/${id}/payments`).then(r => r.data),

  uploadSketchFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/sld-orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },
};
