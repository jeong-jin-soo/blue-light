import axiosClient from './axiosClient';
import type { Invoice, RegenerateInvoiceRequest } from '../types';

/**
 * E-Invoice API Client.
 * PDF 다운로드는 별도 {@code GET /api/files/{pdfFileSeq}/download} 경로를 사용한다
 * — 본 파일은 메타데이터·재발행만 담당.
 */

/** 신청자 본인 — Application의 Invoice 메타 조회. */
export const getMyInvoice = async (applicationId: number): Promise<Invoice> => {
  const response = await axiosClient.get<Invoice>(`/applications/${applicationId}/invoice`);
  return response.data;
};

/** Admin — Application의 Invoice 메타 조회. */
export const getInvoiceAsAdmin = async (applicationId: number): Promise<Invoice> => {
  const response = await axiosClient.get<Invoice>(`/admin/applications/${applicationId}/invoice`);
  return response.data;
};

/** Admin — Invoice PDF 재생성 (스냅샷 불변, PDF만 교체). */
export const regenerateInvoice = async (
  applicationId: number,
  payload: RegenerateInvoiceRequest,
): Promise<Invoice> => {
  const response = await axiosClient.post<Invoice>(
    `/admin/applications/${applicationId}/invoice/regenerate`,
    payload,
  );
  return response.data;
};

/**
 * PDF 다운로드 URL 생성 — InvoiceResponse.pdfFileSeq 를 기존 파일 다운로드 엔드포인트에 연결.
 * axios baseURL과 동일한 prefix를 사용하므로 상대 경로를 반환한다.
 */
export const buildInvoicePdfDownloadUrl = (pdfFileSeq: number): string =>
  `/api/files/${pdfFileSeq}/download`;

const invoiceApi = {
  getMyInvoice,
  getInvoiceAsAdmin,
  regenerateInvoice,
  buildInvoicePdfDownloadUrl,
};

export default invoiceApi;
