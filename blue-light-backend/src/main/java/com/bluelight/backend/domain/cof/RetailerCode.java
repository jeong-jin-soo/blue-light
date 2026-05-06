package com.bluelight.backend.domain.cof;

/**
 * Certificate of Fitness — 전기 리테일러 코드 마스터.
 *
 * <p>lew-review-form-spec.md §6 Step2 Retailer 드롭다운 목록을 그대로 반영.
 * Non-contestable 소비자는 {@link #SP_SERVICES_LIMITED}로 강제되고, Contestable
 * 소비자만 SP 외 리테일러를 자유 선택할 수 있다.</p>
 *
 * <p>마스터 데이터 운영 UI(추가/제거)는 P3에서 별도 검토 — 현재는 enum으로 고정.</p>
 */
public enum RetailerCode {
    SP_SERVICES_LIMITED,
    KEPPEL_ELECTRIC,
    TUAS_POWER_SUPPLY,
    SEMBCORP_POWER,
    GENECO,
    SENOKO_ENERGY_SUPPLY,
    BEST_ELECTRICITY,
    PACIFICLIGHT_ENERGY,
    DIAMOND_ELECTRIC,
    UNION_POWER,
    SUNSEAP_ENERGY,
    OTHER
}
