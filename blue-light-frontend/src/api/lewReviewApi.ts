/**
 * LEW Review Form API client.
 * 백엔드: {@code com.bluelight.backend.api.lew.LewReviewController}.
 *
 * 4개 엔드포인트 래핑:
 * - GET    /api/lew/applications/{id}          → 배정 신청 상세
 * - PUT    /api/lew/applications/{id}/cof      → CoF Draft Save/Upsert
 * - POST   /api/lew/applications/{id}/cof/finalize → 확정 (PR3: 결제 후 호출)
 * - POST   /api/lew/applications/{id}/request-payment → PR3: LEW가 결제 요청 트리거
 *
 * 에러 코드(백엔드 ApiError.code 기준) 핸들링 가이드:
 * - 403 APPLICATION_NOT_ASSIGNED: 배정되지 않은 신청에 접근
 * - 404 APPLICATION_NOT_FOUND  : 신청을 찾을 수 없음
 * - 409 COF_ALREADY_FINALIZED  : 이미 확정된 CoF에 Draft Save/Finalize 시도
 * - 409 COF_VERSION_CONFLICT   : 낙관적 락 충돌
 * - 409 APPLICATION_NOT_PAID   : PR3 — 결제 미완료 상태에서 finalize 시도
 * - 409 INVALID_STATUS_TRANSITION : PR3 — request-payment 호출 시 상태 전제 위반
 * - 409 KVA_NOT_CONFIRMED      : PR3 — request-payment 시 kVA 미확정
 * - 409 DOCUMENT_REQUESTS_PENDING : PR3 — request-payment 시 미해결 서류 요청
 * - 400                       : Finalize 필수 필드 누락/형식 오류
 *
 * axiosClient 인터셉터가 에러를 정규화하여 `{ code, message }`를 포함해 reject한다.
 */

import axiosClient from './axiosClient';
import type { Application } from '../types';
import type {
  CertificateOfFitnessRequest,
  CertificateOfFitnessResponse,
  LewApplicationResponse,
} from '../types/cof';

/** §3.1 — 배정 신청 상세 조회. */
export async function getAssignedApplication(id: number): Promise<LewApplicationResponse> {
  const response = await axiosClient.get<LewApplicationResponse>(`/lew/applications/${id}`);
  return response.data;
}

/** §3.2 — CoF Draft Save / Upsert. */
export async function saveDraftCof(
  id: number,
  request: CertificateOfFitnessRequest,
): Promise<CertificateOfFitnessResponse> {
  const response = await axiosClient.put<CertificateOfFitnessResponse>(
    `/lew/applications/${id}/cof`,
    request,
  );
  return response.data;
}

/**
 * §3.3 — CoF 확정.
 *
 * <p>PR3 옵션 R: 결제 후(PAID/IN_PROGRESS) 단계에서만 호출 가능. status 전이는 발생하지 않으며,
 * CoF.finalized=true + certifiedAt 만 기록된다. SS 638 §13 (시공·테스트 후 CoF 발행) 준수.</p>
 */
export async function finalizeCof(id: number): Promise<Application> {
  const response = await axiosClient.post<Application>(
    `/lew/applications/${id}/cof/finalize`,
  );
  return response.data;
}

/**
 * PR3: LEW가 결제 요청을 트리거 — Phase 1(검토 + 서류 + kVA) 종료 후 호출.
 * status PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT.
 *
 * 가드 위반 시 모두 HTTP 409:
 * - INVALID_STATUS_TRANSITION : status 전제 위반 (이미 PENDING_PAYMENT 등)
 * - KVA_NOT_CONFIRMED         : kVA 미확정
 * - DOCUMENT_REQUESTS_PENDING : 미해결 서류 요청 존재
 */
export async function requestPayment(id: number): Promise<Application> {
  const response = await axiosClient.post<Application>(
    `/lew/applications/${id}/request-payment`,
  );
  return response.data;
}

const lewReviewApi = {
  getAssignedApplication,
  saveDraftCof,
  finalizeCof,
  requestPayment,
};

export default lewReviewApi;
