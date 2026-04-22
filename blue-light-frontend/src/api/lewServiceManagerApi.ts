import api from './axiosClient';
import type {
  LewServiceOrder,
  LewServiceOrderDashboard,
  LewServiceOrderPayment,
  Page,
  ProposeQuoteRequest,
  ScheduleVisitRequest,
} from '../types';

/**
 * LEW Service Manager API — SLD_MANAGER / ADMIN / SYSTEM_ADMIN 전용.
 * Endpoints are `/api/lew-service-manager/**` and mirror the SLD Manager API.
 */
export const lewServiceManagerApi = {
  getDashboard: () =>
    api.get<LewServiceOrderDashboard>('/lew-service-manager/dashboard').then(r => r.data),

  getOrders: (params?: { status?: string; page?: number; size?: number }) =>
    api.get<Page<LewServiceOrder>>('/lew-service-manager/orders', { params }).then(r => r.data),

  getOrder: (id: number) =>
    api.get<LewServiceOrder>(`/lew-service-manager/orders/${id}`).then(r => r.data),

  proposeQuote: (id: number, data: ProposeQuoteRequest) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/propose-quote`, data).then(r => r.data),

  assignManager: (id: number, managerUserSeq: number) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/assign`, { managerUserSeq }).then(r => r.data),

  unassignManager: (id: number) =>
    api.delete<LewServiceOrder>(`/lew-service-manager/orders/${id}/assign`).then(r => r.data),

  uploadDeliverableComplete: (id: number, fileSeq: number, managerNote?: string) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/sld-uploaded`, { fileSeq, managerNote }).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/complete`).then(r => r.data),

  /**
   * 방문 일정 예약 / 재예약 (LEW Service 방문형 리스키닝 PR 2)
   * PAID / IN_PROGRESS / REVISION_REQUESTED 에서 호출. 상태 전이 없음.
   */
  scheduleVisit: (orderId: number, payload: ScheduleVisitRequest) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${orderId}/schedule-visit`, payload).then(r => r.data),

  uploadFile: (orderId: number, file: File, fileType: string) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return api.post(`/lew-service-manager/orders/${orderId}/files`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  getPayments: (id: number) =>
    api.get<LewServiceOrderPayment[]>(`/lew-service-manager/orders/${id}/payments`).then(r => r.data),
};
