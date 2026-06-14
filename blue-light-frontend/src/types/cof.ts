/**
 * LEW Review Form 타입 정의.
 *
 * 백엔드 DTO 대응:
 * - blue-light-backend/src/main/java/com/bluelight/backend/api/lew/dto/LewApplicationResponse.java
 *
 * (Certificate of Fitness 기능은 제거되었다. 본 파일은 LEW 배정 신청 조회 응답 + 신청자 hint
 *  표시에 필요한 공급 유형/리테일러 타입만 보유한다.)
 */

import type { Application } from './index';

/** Consumer Type / Retailer — constants/cof.ts의 타입을 재수출하여 단일 정의원 유지. */
export type { ConsumerType, RetailerCode } from '../constants/cof';

/**
 * LEW 배정 신청 상세 응답.
 *
 * 주의: 백엔드는 `application` 필드 안에 전체 ApplicationResponse를 **중첩**해 반환한다.
 * TypeScript의 Application 타입에 MSSL/hint 응답 필드가 이미 포함되어 있으므로 그대로 재사용한다.
 * Correspondence/Landlord 평문은 LEW 전용으로 별도 최상위 필드에 노출된다.
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
  msslHintPlain?: string;
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
}
