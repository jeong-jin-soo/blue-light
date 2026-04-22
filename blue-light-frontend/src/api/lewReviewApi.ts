/**
 * LEW Review Form API client.
 * 백엔드: {@code com.bluelight.backend.api.lew.LewReviewController} (P1.A 완료).
 *
 * 3개 엔드포인트 래핑:
 * - GET    /api/lew/applications/{id}          → 배정 신청 상세
 * - PUT    /api/lew/applications/{id}/cof      → CoF Draft Save/Upsert
 * - POST   /api/lew/applications/{id}/cof/finalize → 확정 → status=PENDING_PAYMENT
 *
 * 에러 코드(백엔드 ApiError.code 기준) 핸들링 가이드:
 * - 403 APPLICATION_NOT_ASSIGNED: 배정되지 않은 신청에 접근
 * - 404 APPLICATION_NOT_FOUND  : 신청을 찾을 수 없음
 * - 409 COF_ALREADY_FINALIZED  : 이미 확정된 CoF에 Draft Save/Finalize 시도
 * - 409 COF_VERSION_CONFLICT   : 낙관적 락 충돌
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

/** §3.3 — CoF 확정 (status PENDING_REVIEW → PENDING_PAYMENT). */
export async function finalizeCof(id: number): Promise<Application> {
  const response = await axiosClient.post<Application>(
    `/lew/applications/${id}/cof/finalize`,
  );
  return response.data;
}

const lewReviewApi = {
  getAssignedApplication,
  saveDraftCof,
  finalizeCof,
};

export default lewReviewApi;
