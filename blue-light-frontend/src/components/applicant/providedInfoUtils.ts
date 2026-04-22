import type { Application } from '../../types';

/**
 * 신청자가 P2.A Optional Fast-Track 섹션에 hint를 하나라도 제공했는지 여부.
 *
 * ProvidedInfoCard 호출부에서 early-return 분기에 사용. 컴포넌트 파일과 분리해 둔 이유는
 * Vite fast-refresh가 "컴포넌트 파일에서 컴포넌트 외 심볼을 export하면" 경고하기 때문 —
 * `react-refresh/only-export-components` 규칙(ProvidedInfoCard.tsx에서 이 함수를 함께
 * export하면 발생).
 */
export function hasAnyProvidedInfo(application: Application): boolean {
  return !!(
    application.msslHintLast4 ||
    application.supplyVoltageHint ||
    application.consumerTypeHint ||
    application.retailerHint ||
    application.hasGeneratorHint ||
    application.generatorCapacityHint
  );
}
