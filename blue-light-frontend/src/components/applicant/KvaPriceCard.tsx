import type { PriceCalculation, MasterPrice } from '../../types';

interface KvaPriceCardProps {
  kvaUnknown: boolean;
  selectedKva: number | null;
  priceResult: PriceCalculation | null;
  priceTiers: MasterPrice[];
  renewalPeriodMonths?: number | null;
}

/**
 * KvaPriceCard — Step 2 kVA/가격 요약 + 가격 분석표 (Phase 5 PR#2)
 *
 * - UNKNOWN: "From S$350" (dashed underline, primary tone) + "Final price after LEW confirms your kVA"
 *   가격 분석표는 opacity-50 + "Will activate after LEW confirms" 배지.
 * - CONFIRMED: 기존 Price breakdown + reference table 그대로 노출.
 *
 * 04-design-spec.md KvaPriceCard §. Phase 1 InfoBox 톤(primary-700/800) 재사용.
 */
export function KvaPriceCard({
  kvaUnknown,
  selectedKva,
  priceResult,
  priceTiers,
  renewalPeriodMonths,
}: KvaPriceCardProps) {
  // "From" 최소가 — 가장 낮은 kVA 티어(일반적으로 45 kVA) 기준 price
  const minTier = priceTiers.length > 0
    ? priceTiers.reduce((min, t) => (t.price < min.price ? t : min), priceTiers[0])
    : null;
  const fromPrice = minTier?.price ?? 350;

  if (kvaUnknown) {
    return (
      <div className="space-y-5">
        {/* From S$XXX card */}
        <div className="bg-primary-50 rounded-xl p-5 border border-primary-100 space-y-2">
          <p className="text-sm font-medium text-primary-700">Estimated Starting Price</p>
          <div className="flex items-baseline gap-2">
            <span className="text-sm text-primary-700">From</span>
            <span
              className="text-2xl font-bold text-primary-800 underline decoration-dashed decoration-primary-400 underline-offset-4"
              title="Minimum price — final amount depends on confirmed kVA"
            >
              S${fromPrice.toLocaleString()}
            </span>
          </div>
          <p className="text-xs text-primary-600">
            Final price after LEW confirms your kVA. Confirmation typically within 1 business day.
          </p>
        </div>

        {/* Price reference table — deactivated look */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-sm font-medium text-gray-600">Price Reference Table</h3>
            <span className="inline-flex items-center gap-1 px-2 py-0.5 text-[10px] font-semibold text-warning-800 bg-warning-50 border border-warning-500/40 rounded-full">
              Will activate after LEW confirms
            </span>
          </div>
          <div className="border border-gray-200 rounded-lg overflow-x-auto opacity-50 pointer-events-none">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50">
                  <th className="text-left py-2 px-3 font-medium text-gray-600">Capacity</th>
                  <th className="text-right py-2 px-3 font-medium text-gray-600">Price (SGD)</th>
                </tr>
              </thead>
              <tbody>
                {priceTiers.map((tier) => (
                  <tr key={tier.masterPriceSeq} className="border-t border-gray-100">
                    <td className="py-2 px-3">{tier.description}</td>
                    <td className="py-2 px-3 text-right">${tier.price.toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    );
  }

  // CONFIRMED: 기존 포맷 유지
  return (
    <div className="space-y-5">
      {priceResult && (
        <div className="bg-primary-50 rounded-xl p-5 border border-primary-100 space-y-3">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-primary-700">Selected Tier</p>
              <p className="text-lg font-semibold text-primary-800 mt-1">{priceResult.tierDescription}</p>
            </div>
          </div>
          <div className="border-t border-primary-200 pt-3 space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-primary-700">kVA Tier Price</span>
              <span className="font-medium text-primary-800">SGD ${priceResult.price.toLocaleString()}</span>
            </div>
            {priceResult.sldFee != null && priceResult.sldFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">SLD Drawing Fee</span>
                <span className="font-medium text-primary-800">SGD ${priceResult.sldFee.toLocaleString()}</span>
              </div>
            )}
            {priceResult.emaFee != null && priceResult.emaFee > 0 && (
              <div className="flex justify-between text-sm">
                <span className="text-primary-700">EMA Fee ({renewalPeriodMonths}-month)</span>
                <span className="font-medium text-primary-800">SGD ${priceResult.emaFee.toLocaleString()}</span>
              </div>
            )}
            <div className="border-t border-primary-200 pt-2 flex justify-between">
              <span className="text-sm font-semibold text-primary-700">Total Amount</span>
              <span className="text-xl font-bold text-primary-800">
                SGD ${priceResult.totalAmount.toLocaleString()}
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Price reference table */}
      <div>
        <h3 className="text-sm font-medium text-gray-600 mb-2">Price Reference Table</h3>
        <div className="border border-gray-200 rounded-lg overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50">
                <th className="text-left py-2 px-3 font-medium text-gray-600">Capacity</th>
                <th className="text-right py-2 px-3 font-medium text-gray-600">Price (SGD)</th>
              </tr>
            </thead>
            <tbody>
              {priceTiers.map((tier) => (
                <tr
                  key={tier.masterPriceSeq}
                  className={`border-t border-gray-100 ${
                    selectedKva && selectedKva >= tier.kvaMin && selectedKva <= tier.kvaMax
                      ? 'bg-primary-50 font-medium'
                      : ''
                  }`}
                >
                  <td className="py-2 px-3">{tier.description}</td>
                  <td className="py-2 px-3 text-right">${tier.price.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
