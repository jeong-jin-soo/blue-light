import api from './axiosClient';
import type { LightingOrder, CreateLightingOrderRequest, LightingOrderPayment } from '../types';

export const lightingOrderApi = {
  createLightingOrder: (data: CreateLightingOrderRequest) =>
    api.post<LightingOrder>('/lighting-orders', data).then(r => r.data),

  getMyLightingOrders: () =>
    api.get<LightingOrder[]>('/lighting-orders').then(r => r.data),

  getLightingOrder: (id: number) =>
    api.get<LightingOrder>(`/lighting-orders/${id}`).then(r => r.data),

  updateLightingOrder: (id: number, data: { applicantNote?: string; sketchFileSeq?: number }) =>
    api.put<LightingOrder>(`/lighting-orders/${id}`, data).then(r => r.data),

  acceptQuote: (id: number) =>
    api.post<LightingOrder>(`/lighting-orders/${id}/accept-quote`).then(r => r.data),

  rejectQuote: (id: number) =>
    api.post<LightingOrder>(`/lighting-orders/${id}/reject-quote`).then(r => r.data),

  requestRevision: (id: number, comment: string) =>
    api.post<LightingOrder>(`/lighting-orders/${id}/request-revision`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<LightingOrder>(`/lighting-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<LightingOrderPayment[]>(`/lighting-orders/${id}/payments`).then(r => r.data),

};