import api from './axiosClient';
import type { LewServiceOrder, CreateLewServiceOrderRequest, LewServiceOrderPayment } from '../types';

export const lewServiceOrderApi = {
  createLewServiceOrder: (data: CreateLewServiceOrderRequest) =>
    api.post<LewServiceOrder>('/lew-service-orders', data).then(r => r.data),

  getMyLewServiceOrders: () =>
    api.get<LewServiceOrder[]>('/lew-service-orders').then(r => r.data),

  getLewServiceOrder: (id: number) =>
    api.get<LewServiceOrder>(`/lew-service-orders/${id}`).then(r => r.data),

  updateLewServiceOrder: (id: number, data: { applicantNote?: string; sketchFileSeq?: number }) =>
    api.put<LewServiceOrder>(`/lew-service-orders/${id}`, data).then(r => r.data),

  acceptQuote: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/accept-quote`).then(r => r.data),

  rejectQuote: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/reject-quote`).then(r => r.data),

  requestRevision: (id: number, comment: string) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/request-revision`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<LewServiceOrderPayment[]>(`/lew-service-orders/${id}/payments`).then(r => r.data),

};