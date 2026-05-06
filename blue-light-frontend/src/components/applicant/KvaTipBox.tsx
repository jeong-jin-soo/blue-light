import { InfoBox } from '../ui/InfoBox';

interface KvaTipBoxProps {
  /** 선택된 buildingType (Step 1). 비어있으면 일반 안내 표시. */
  buildingType?: string;
  className?: string;
}

/**
 * KvaTipBox — "Not sure about your kVA?" 안내 (Phase 5 PR#2)
 *
 * buildingType 기반 조건부 hint + 일반 안내(SP Group bill / main breaker).
 * pre-select 하지 않음(UX 요구) — 사용자에게 선택을 강제하지 않고 "I don't know" 옵션을 유도.
 */
export function KvaTipBox({ buildingType, className }: KvaTipBoxProps) {
  const normalized = (buildingType || '').trim().toLowerCase();

  // Building type별 일반적인 범위 안내 (04-design-spec.md KvaTipBox §)
  let hint: string | null = null;
  if (normalized === 'residential') {
    // Residential은 HDB/Condo/Apartment 통합 — 가장 흔한 45 kVA 안내
    hint = 'HDB flats and apartments are typically 45 kVA. Landed properties can range 45–100 kVA.';
  } else if (normalized === 'commercial' || normalized === 'mixed use') {
    hint = 'Small offices and shops typically range 100–500 kVA. Shophouses commonly 45–200 kVA.';
  } else if (normalized === 'industrial') {
    hint = 'Factories and industrial sites typically start at 500 kVA and scale up with machinery load.';
  } else if (normalized === 'hotel' || normalized === 'healthcare') {
    hint = 'Hotels and healthcare facilities commonly range 500–1500 kVA depending on size and equipment.';
  } else if (normalized === 'education' || normalized === 'government') {
    hint = 'Institutional buildings typically range 200–1000 kVA depending on facility size.';
  }

  return (
    <InfoBox title="Not sure about your kVA?" className={className}>
      {hint && <p className="mb-1">{hint}</p>}
      <p>
        You can find your kVA on your SP Group bill, or check the rating label on your main
        circuit breaker. If you're still unsure, select{' '}
        <span className="italic">"I don't know"</span> and your LEW will confirm it for you.
      </p>
    </InfoBox>
  );
}
