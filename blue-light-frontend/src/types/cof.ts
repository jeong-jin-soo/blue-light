/**
 * LEW Review Form / Certificate of Fitness 타입 정의.
 *
 * 백엔드 DTO 대응:
 * - blue-light-backend/src/main/java/com/bluelight/backend/api/lew/dto/CertificateOfFitnessRequest.java
 * - blue-light-backend/src/main/java/com/bluelight/backend/api/lew/dto/CertificateOfFitnessResponse.java
 * - blue-light-backend/src/main/java/com/bluelight/backend/api/lew/dto/LewApplicationResponse.java
 */

import type { Application } from './index';

/** Consumer Type — constants/cof.ts의 타입을 재수출하여 단일 정의원 유지. */
export type { ConsumerType, RetailerCode } from '../constants/cof';

/** 점검 주기(개월) — DB CHECK 제약: 6/12/24/36/60. */
export type InspectionInterval = 6 | 12 | 24 | 36 | 60;

/** 공급 전압(V) — DB CHECK 제약: 230/400/6600/22000. */
export type SupplyVoltage = 230 | 400 | 6600 | 22000;

/**
 * CoF 입력 DTO (Draft Save / Finalize 공용 본문).
 * 서버 엔드포인트: `PUT /api/lew/applications/{id}/cof`.
 * 모든 필드는 Draft 저장 시 nullable. Finalize는 서버가 필수 필드 전수 재검증.
 */
export interface CertificateOfFitnessRequest {
  msslAccountNo?: string;           // "AAA-BB-CCCC-D"
  consumerType?: import('../constants/cof').ConsumerType;
  retailerCode?: import('../constants/cof').RetailerCode;
  supplyVoltageV?: SupplyVoltage;
  approvedLoadKva?: number;
  hasGenerator?: boolean;
  generatorCapacityKva?: number;
  inspectionIntervalMonths?: InspectionInterval;
  lewAppointmentDate?: string;      // ISO date "yyyy-MM-dd"
  lewConsentDate?: string;          // ISO date "yyyy-MM-dd"
}

/**
 * CoF 조회/저장 응답 DTO. LEW 컨트롤러만 이 형식을 반환하며 MSSL 평문을 포함한다.
 * ADMIN/APPLICANT 대상 응답은 별도 DTO가 사용됨.
 */
export interface CertificateOfFitnessResponse {
  cofSeq: number;
  applicationSeq: number;
  msslAccountNo?: string;           // LEW 전용 평문
  msslAccountNoLast4?: string;
  consumerType?: import('../constants/cof').ConsumerType;
  retailerCode?: import('../constants/cof').RetailerCode;
  supplyVoltageV?: number;
  approvedLoadKva?: number;
  hasGenerator?: boolean;
  generatorCapacityKva?: number;
  inspectionIntervalMonths?: number;
  lewAppointmentDate?: string;
  lewConsentDate?: string;
  certifiedByLewSeq?: number;
  certifiedAt?: string;
  draftSavedAt?: string;
  /** 낙관적 락 version — PUT 시 에코되어 돌아옴. */
  version: number;
  /** finalize 완료 여부 (편의 플래그). */
  finalized?: boolean;
}

/**
 * LEW 배정 신청 상세 응답.
 *
 * 주의: 백엔드는 `application` 필드 안에 전체 ApplicationResponse를 **중첩**해 반환한다
 * (LewApplicationResponse.java line 21). TypeScript의 Application 타입에 MSSL/hint 응답 필드가
 * 이미 포함되어 있으므로 그대로 재사용한다. Correspondence/Landlord 평문은 LEW 전용으로
 * 별도 최상위 필드에 노출된다.
 */
export interface LewApplicationResponse {
  application: Application;

  // LEW 전용 평문 노출 필드
  landlordEiLicenceNo?: string;
  correspondenceAddressBlockPlain?: string;
  correspondenceAddressUnitPlain?: string;
  correspondenceAddressStreetPlain?: string;
  correspondenceAddressBuildingPlain?: string;

  // 신청자 hint 원본 — MSSL은 last4(마스킹) + 평문(prefill용)을 모두 LEW에게 전달
  msslHintLast4?: string;
  msslHintPlain?: string;           // LEW Review Form Step 2 prefill용 (LEW만 열람)
  supplyVoltageHint?: number;
  consumerTypeHint?: import('../constants/cof').ConsumerType;
  retailerHint?: import('../constants/cof').RetailerCode;
  hasGeneratorHint?: boolean;
  generatorCapacityHint?: number;

  // "신청자 기입값" 배지 렌더링용 플래그
  msslHintProvided?: boolean;
  supplyVoltageHintProvided?: boolean;
  consumerTypeHintProvided?: boolean;
  retailerHintProvided?: boolean;
  generatorHintProvided?: boolean;

  /** CoF Draft. null이면 LEW가 아직 저장하지 않은 상태. */
  cof?: CertificateOfFitnessResponse;
}
