package com.bluelight.backend.domain.application;

/**
 * 전기 리테일러 코드 마스터 — 신청자 hint 검증에 사용.
 *
 * <p>Non-contestable 소비자는 {@link #SP_SERVICES_LIMITED}로 강제되고, Contestable
 * 소비자만 SP 외 리테일러를 자유 선택할 수 있다.</p>
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
