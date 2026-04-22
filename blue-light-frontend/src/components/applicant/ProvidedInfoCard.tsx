import { Badge } from '../ui/Badge';
import { Card } from '../ui/Card';
import {
  CONSUMER_TYPE_OPTIONS,
  RETAILER_OPTIONS,
  SUPPLY_VOLTAGE_OPTIONS,
} from '../../constants/cof';
import type { Application } from '../../types';

/**
 * 신청자가 P2.A Optional Fast-Track 섹션에 입력해 제출한 hint 값을
 * 신청 상세 화면에 다시 보여주는 카드.
 *
 * 부채감 제거 원칙 (스펙 §5.1·§9-17):
 * - "입력되지 않음", "누락", "필수" 등의 부정/책임형 어휘 금지.
 * - 제공된 항목만 노출, 미제공 항목은 DOM에 등장시키지 않음.
 * - 제공된 항목 수가 0이면 컴포넌트는 {@code null}을 반환해 섹션 자체가 사라짐.
 *
 * 색상 톤: 제공됨 배지는 success 계열 — "당신이 준 정보가 이만큼 쌓였어요" 라는 누적 감각.
 */
export interface ProvidedInfoCardProps {
  application: Application;
}

// hasAnyProvidedInfo 헬퍼는 fast-refresh 규칙상 별도 파일에 있음 — `./providedInfoUtils`.

export function ProvidedInfoCard({ application }: ProvidedInfoCardProps) {
  // 입력된 항목만 뽑아 렌더 목록 생성.
  // MSSL last4 기준 판정: last4만 오므로 맨 뒤 1자리만 노출 (UX는 P2.A StepReview와 동일).
  const items: Array<{ key: string; label: string; value: string }> = [];

  if (application.msslHintLast4) {
    const last = application.msslHintLast4.slice(-1);
    items.push({
      key: 'mssl',
      label: 'MSSL Account No',
      value: `•••-••-••••-${last}`,
    });
  }

  if (application.consumerTypeHint) {
    const opt = CONSUMER_TYPE_OPTIONS.find((o) => o.value === application.consumerTypeHint);
    items.push({
      key: 'consumer',
      label: 'Consumer Type',
      value: opt ? opt.label : application.consumerTypeHint,
    });
  }

  if (application.retailerHint) {
    const opt = RETAILER_OPTIONS.find((o) => o.value === application.retailerHint);
    items.push({
      key: 'retailer',
      label: 'Retailer',
      value: opt ? opt.label : application.retailerHint,
    });
  }

  if (application.supplyVoltageHint) {
    const opt = SUPPLY_VOLTAGE_OPTIONS.find((o) => o.value === application.supplyVoltageHint);
    items.push({
      key: 'voltage',
      label: 'Supply Voltage',
      value: opt ? opt.label : `${application.supplyVoltageHint}V`,
    });
  }

  // Generator — hasGeneratorHint가 true일 때만 표시. false는 명시적 "없음" 신호이긴 하나,
  // 부채감 제거 원칙상 "No"를 굳이 카드에 드러낼 이유가 없어 생략한다.
  // (generatorCapacityHint만 있고 hasGenerator가 false인 비대칭 케이스도 무시 — 드문 경우.)
  if (application.hasGeneratorHint === true) {
    const cap = application.generatorCapacityHint;
    items.push({
      key: 'generator',
      label: 'Generator',
      value: cap != null ? `Yes — ${cap} kVA` : 'Yes',
    });
  }

  if (items.length === 0) {
    // 최종 방어 — 호출부가 hasAnyProvidedInfo()로 이미 걸렀지만,
    // generatorHint=false 같은 케이스로 모든 항목이 제외될 수 있음.
    return null;
  }

  return (
    <Card>
      <div className="space-y-3">
        <div className="flex items-start gap-2">
          <div
            className="flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-full bg-success-50 text-success-700"
            aria-hidden="true"
          >
            <svg className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth={2.5} viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <div>
            <h3 className="text-sm font-semibold text-gray-800">
              Fast-track info you provided
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Your LEW will use these when reviewing on site.
            </p>
          </div>
        </div>

        <dl className="grid grid-cols-1 sm:grid-cols-2 gap-3 pt-1">
          {items.map((it) => (
            <div key={it.key}>
              <dt className="flex items-center gap-1.5 text-xs text-gray-500">
                <Badge variant="success">Provided</Badge>
                <span>{it.label}</span>
              </dt>
              <dd className="mt-1 text-sm font-medium text-gray-800">{it.value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </Card>
  );
}
