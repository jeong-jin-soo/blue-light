import api from './axiosClient';
import type {
  ExpiredLicenseOrder,
  CreateExpiredLicenseOrderRequest,
  ExpiredLicenseOrderPayment,
  ExpiredLicenseSupportingDocument,
} from '../types';

export const expiredLicenseOrderApi = {
  createExpiredLicenseOrder: (data: CreateExpiredLicenseOrderRequest) =>
    api.post<ExpiredLicenseOrder>('/expired-license-orders', data).then(r => r.data),

  getMyExpiredLicenseOrders: () =>
    api.get<ExpiredLicenseOrder[]>('/expired-license-orders').then(r => r.data),

  getExpiredLicenseOrder: (id: number) =>
    api.get<ExpiredLicenseOrder>(`/expired-license-orders/${id}`).then(r => r.data),

  updateExpiredLicenseOrder: (id: number, data: { applicantNote?: string }) =>
    api.put<ExpiredLicenseOrder>(`/expired-license-orders/${id}`, data).then(r => r.data),

  acceptQuote: (id: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-orders/${id}/accept-quote`).then(r => r.data),

  rejectQuote: (id: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-orders/${id}/reject-quote`).then(r => r.data),

  requestRevisit: (id: number, comment: string) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-orders/${id}/request-revisit`, { comment }).then(r => r.data),

  confirmCompletion: (id: number) =>
    api.post<ExpiredLicenseOrder>(`/expired-license-orders/${id}/confirm`).then(r => r.data),

  getPayments: (id: number) =>
    api.get<ExpiredLicenseOrderPayment[]>(`/expired-license-orders/${id}/payments`).then(r => r.data),

  /**
   * 참고 문서 업로드 (파일당 20MB, 주문당 최대 10개, 임의 포맷).
   */
  uploadSupportingDocument: (orderId: number, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post<ExpiredLicenseSupportingDocument>(
      `/expired-license-orders/${orderId}/files`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    ).then(r => r.data);
  },

  /**
   * 참고 문서 삭제.
   */
  deleteSupportingDocument: (orderId: number, fileSeq: number) =>
    api.delete(`/expired-license-orders/${orderId}/files/${fileSeq}`).then(r => r.data),
};
