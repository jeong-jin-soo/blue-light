import axiosClient from './axiosClient';
import type { Page } from '../types';
import type {
  ManualEmailDispatchHistoryItem,
  ManualEmailDispatchResponse,
  ManualEmailHistoryFilter,
  ManualEmailPreviewResponse,
  SendManualEmailRequest,
} from '../types/manualEmail';

/**
 * ADMIN 수동 이메일 발송 API 클라이언트 (PR-3).
 *
 * 백엔드: {@code AdminManualEmailController} ({@code /api/admin/manual-emails}).
 * 권한: ADMIN / SYSTEM_ADMIN.
 *
 * 스펙: doc/Project Analysis/admin-manual-email-spec.md §5.
 */

/**
 * 발송 — POST /api/admin/manual-emails.
 *
 * `forceDuplicate` 옵션이 true 면 30초 이내 멱등성 가드(D3=B) 우회. 첫 호출 시 409
 * `MANUAL_EMAIL_DUPLICATE_SUSPECTED` 가 떨어지면 사용자에게 confirm 받고 한 번만 재호출한다.
 */
export const sendManualEmail = async (
  payload: SendManualEmailRequest,
  options?: { forceDuplicate?: boolean }
): Promise<ManualEmailDispatchResponse> => {
  const body: SendManualEmailRequest = {
    ...payload,
    ...(options?.forceDuplicate ? { forceDuplicate: true } : {}),
  };
  const response = await axiosClient.post<ManualEmailDispatchResponse>(
    '/admin/manual-emails',
    body
  );
  return response.data;
};

/**
 * 미리보기 — POST /api/admin/manual-emails/preview.
 * 트랜잭션·DB 영향 0. ADMIN 본문에 자동 헤더·푸터 부착된 HTML 만 반환.
 */
export const previewManualEmail = async (
  payload: SendManualEmailRequest
): Promise<ManualEmailPreviewResponse> => {
  const response = await axiosClient.post<ManualEmailPreviewResponse>(
    '/admin/manual-emails/preview',
    payload
  );
  return response.data;
};

/**
 * 발송 이력 목록 — GET /api/admin/manual-emails (Page&lt;ManualEmailDispatchHistoryItem&gt;).
 */
export const getManualEmailHistory = async (
  filter: ManualEmailHistoryFilter = {},
  page = 0,
  size = 20
): Promise<Page<ManualEmailDispatchHistoryItem>> => {
  const response = await axiosClient.get<Page<ManualEmailDispatchHistoryItem>>(
    '/admin/manual-emails',
    {
      params: {
        page,
        size,
        ...(filter.senderUserSeq != null && { senderUserSeq: filter.senderUserSeq }),
        ...(filter.dispatchStatus && { dispatchStatus: filter.dispatchStatus }),
        ...(filter.relatedApplicationSeq != null && {
          relatedApplicationSeq: filter.relatedApplicationSeq,
        }),
        ...(filter.from && { from: filter.from }),
        ...(filter.to && { to: filter.to }),
      },
    }
  );
  return response.data;
};

/** 단건 상세 — GET /api/admin/manual-emails/{seq} (전체 본문 포함). */
export const getManualEmailDetail = async (
  dispatchSeq: number
): Promise<ManualEmailDispatchHistoryItem> => {
  const response = await axiosClient.get<ManualEmailDispatchHistoryItem>(
    `/admin/manual-emails/${dispatchSeq}`
  );
  return response.data;
};

const adminManualEmailApi = {
  sendManualEmail,
  previewManualEmail,
  getManualEmailHistory,
  getManualEmailDetail,
};

export default adminManualEmailApi;
