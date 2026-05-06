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

  /**
   * 재방문 요청 — PR 3. 기존 requestRevision 엔드포인트 대체.
   */
  requestRevisit: (id: number, comment: string) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/request-revisit`, { comment }).then(r => r.data),

  /**
   * @deprecated PR 3 — requestRevisit 사용 권장. 하위호환용으로 1 개월 유지.
   */
  requestRevision: (id: number, comment: string) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/request-revision`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<LewServiceOrderPayment[]>(`/lew-service-orders/${id}/payments`).then(r => r.data),

  uploadSketchFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/lew-service-orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },
};
