import api from './axiosClient';
import type { PowerSocketOrder, CreatePowerSocketOrderRequest, PowerSocketOrderPayment } from '../types';

export const powerSocketOrderApi = {
  createPowerSocketOrder: (data: CreatePowerSocketOrderRequest) =>
    api.post<PowerSocketOrder>('/power-socket-orders', data).then(r => r.data),

  getMyPowerSocketOrders: () =>
    api.get<PowerSocketOrder[]>('/power-socket-orders').then(r => r.data),

  getPowerSocketOrder: (id: number) =>
    api.get<PowerSocketOrder>(`/power-socket-orders/${id}`).then(r => r.data),

  updatePowerSocketOrder: (id: number, data: { applicantNote?: string; sketchFileSeq?: number }) =>
    api.put<PowerSocketOrder>(`/power-socket-orders/${id}`, data).then(r => r.data),

  acceptQuote: (id: number) =>
    api.post<PowerSocketOrder>(`/power-socket-orders/${id}/accept-quote`).then(r => r.data),

  rejectQuote: (id: number) =>
    api.post<PowerSocketOrder>(`/power-socket-orders/${id}/reject-quote`).then(r => r.data),

  requestRevision: (id: number, comment: string) =>
    api.post<PowerSocketOrder>(`/power-socket-orders/${id}/request-revision`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<PowerSocketOrder>(`/power-socket-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<PowerSocketOrderPayment[]>(`/power-socket-orders/${id}/payments`).then(r => r.data),

  uploadSketchFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/power-socket-orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },
};
