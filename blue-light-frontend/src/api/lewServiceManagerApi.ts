import api from './axiosClient';
import type {
  CheckOutRequest,
  LewServiceOrder,
  LewServiceOrderDashboard,
  LewServiceOrderPayment,
  Page,
  ProposeQuoteRequest,
  ScheduleVisitRequest,
} from '../types';

/**
 * LEW Service Manager API — SLD_MANAGER / ADMIN / SYSTEM_ADMIN 전용.
 * Endpoints are `/api/lew-service-manager/**`.
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

  /**
   * @deprecated PR 3 — 구 도면 업로드. 하위호환용 1 개월 유지.
   *   신규는 {@link checkOut} 사용.
   */
  uploadDeliverableComplete: (id: number, fileSeq: number, managerNote?: string) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/sld-uploaded`, { fileSeq, managerNote }).then(r => r.data),

  confirmPayment: (id: number, transactionId?: string, paymentMethod?: string) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/payment/confirm`, { transactionId, paymentMethod }).then(r => r.data),

  markComplete: (id: number) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${id}/complete`).then(r => r.data),

  /**
   * 방문 일정 예약 / 재예약 (LEW Service 방문형 리스키닝 PR 2)
   * PAID / VISIT_SCHEDULED / REVISIT_REQUESTED 에서 호출. 상태 전이 없음.
   */
  scheduleVisit: (orderId: number, payload: ScheduleVisitRequest) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${orderId}/schedule-visit`, payload).then(r => r.data),

  /**
   * 체크인 — PR 3. VISIT_SCHEDULED 에서 호출.
   */
  checkIn: (orderId: number) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${orderId}/check-in`).then(r => r.data),

  /**
   * 체크아웃 + 방문 보고서 제출 — PR 3.
   */
  checkOut: (orderId: number, payload: CheckOutRequest) =>
    api.post<LewServiceOrder>(`/lew-service-manager/orders/${orderId}/check-out`, payload).then(r => r.data),

  /**
   * 방문 사진 다건 업로드 — PR 3.
   * @param files 최대 10장
   * @param captions 각 파일에 대응하는 캡션 (optional)
   */
  uploadVisitPhotos: (orderId: number, files: File[], captions?: (string | undefined)[]) => {
    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
    if (captions) {
      captions.forEach((c) => formData.append('captions', c ?? ''));
    }
    return api.post<LewServiceOrder>(
      `/lew-service-manager/orders/${orderId}/visit-photos`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    ).then(r => r.data);
  },

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
