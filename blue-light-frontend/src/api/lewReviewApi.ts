/**
 * LEW Review Form API client.
 * 백엔드: {@code com.bluelight.backend.api.lew.LewReviewController}.
 *
 * 2개 엔드포인트 래핑:
 * - GET    /api/lew/applications/{id}          → 배정 신청 상세
 * - POST   /api/lew/applications/{id}/request-payment → LEW가 결제 요청 트리거
 *
 * 에러 코드(백엔드 ApiError.code 기준) 핸들링 가이드:
 * - 403 APPLICATION_NOT_ASSIGNED: 배정되지 않은 신청에 접근
 * - 404 APPLICATION_NOT_FOUND  : 신청을 찾을 수 없음
 * - 409 INVALID_STATUS_TRANSITION : request-payment 호출 시 상태 전제 위반
 * - 409 KVA_NOT_CONFIRMED      : request-payment 시 kVA 미확정
 * - 409 DOCUMENT_REQUESTS_PENDING : request-payment 시 미해결 서류 요청
 *
 * axiosClient 인터셉터가 에러를 정규화하여 `{ code, message }`를 포함해 reject한다.
 */

import axiosClient from './axiosClient';
import type { Application } from '../types';
import type { LewApplicationResponse } from '../types/cof';

/** 배정 신청 상세 조회. */
export async function getAssignedApplication(id: number): Promise<LewApplicationResponse> {
  const response = await axiosClient.get<LewApplicationResponse>(`/lew/applications/${id}`);
  return response.data;
}

/**
 * LEW가 결제 요청을 트리거 — Phase 1(검토 + 서류 + kVA) 종료 후 호출.
 * status PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT.
 *
 * 가드 위반 시 모두 HTTP 409:
 * - INVALID_STATUS_TRANSITION : status 전제 위반 (이미 PENDING_PAYMENT 등)
 * - KVA_NOT_CONFIRMED         : kVA 미확정
 * - DOCUMENT_REQUESTS_PENDING : 미해결 서류 요청 존재
 */
export async function requestPayment(id: number, addSldFee = false): Promise<Application> {
  const response = await axiosClient.post<Application>(
    `/lew/applications/${id}/request-payment`,
    null,
    { params: addSldFee ? { addSldFee: true } : undefined },
  );
  return response.data;
}

// ── PR-3: 결제 후 kVA 변경 요청 (LEW → ADMIN) ─────────────────────────────
// 스펙: doc/Project Analysis/kva-postpayment-adjustment-spec.md §4.2

/** PR-3 요청 payload — proposedKva 는 master_prices 에 등록된 활성 tier 만 허용. */
export interface LewKvaAdjustmentPayload {
  proposedKva: number;
  reason: string;
}

/** PR-3 응답 — KvaAdjustmentRecord 의 PENDING_ADMIN_REVIEW row. */
export interface LewKvaAdjustmentResponse {
  adjustmentSeq: number;
  status: 'PENDING_ADMIN_REVIEW' | 'APPLIED' | 'RESOLVED_BY_ADMIN_OVERRIDE' | 'REJECTED' | 'CANCELLED';
  proposedKva: number;
  currentKva: number;
  reason: string;
  createdAt: string;
}

/**
 * §4.2 PR-3 — LEW 가 결제 후 kVA 변경을 ADMIN 에게 요청.
 *
 * 가드 위반 코드:
 * - 403 APPLICATION_NOT_ASSIGNED — 배정 LEW 가 아님
 * - 409 KVA_NOT_POSTPAYMENT — PRE-PAYMENT 상태
 * - 409 KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED — EXPIRED 상태
 * - 409 KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING — 동일 application 에 PENDING 요청 존재
 * - 400 KVA_NO_CHANGE — 동일 proposedKva
 * - 400 INVALID_KVA_TIER — master_prices 미존재
 */
export async function requestKvaAdjustment(
  id: number,
  payload: LewKvaAdjustmentPayload,
): Promise<LewKvaAdjustmentResponse> {
  const response = await axiosClient.post<LewKvaAdjustmentResponse>(
    `/lew/applications/${id}/kva-adjustment-request`,
    payload,
  );
  return response.data;
}

/** SLD 전환(E2) 응답 — sld-lew-conversion-fee-spec.md §9. */
export interface SldConversionResponse {
  applicationSeq: number;
  sldFee: number;
  newQuoteAmount: number;
  /** true 면 결제 후 전환 → 보충 청구(정산 원장 PENDING) 발생. */
  postPayment: boolean;
  adjustmentSeq: number | null;
}

/**
 * E2 — 담당 LEW 가 SLD self-upload → 본인 작성(REQUEST_LEW)으로 전환 + SLD 작성비 청구.
 *
 * 가드 위반 코드:
 * - 409 SLD_ALREADY_LEW — 이미 REQUEST_LEW
 * - 409 SLD_CONVERT_NOT_ALLOWED — COMPLETED/EXPIRED
 * - 404 PRICE_TIER_NOT_FOUND — master_prices 미존재
 */
export async function convertSldToLew(id: number): Promise<SldConversionResponse> {
  const response = await axiosClient.post<SldConversionResponse>(
    `/lew/applications/${id}/sld/convert-to-lew`,
  );
  return response.data;
}

const lewReviewApi = {
  getAssignedApplication,
  requestPayment,
  requestKvaAdjustment,
  convertSldToLew,
};

export default lewReviewApi;
