package com.bluelight.backend.domain.cof;

/**
 * Certificate of Fitness — 전력 소비자 유형.
 *
 * <p>SP Group / EMA 규정에 따른 구분:
 * <ul>
 *   <li>{@link #NON_CONTESTABLE}: 소형 수용가, SP Services가 기본 리테일러.</li>
 *   <li>{@link #CONTESTABLE}: 45kVA 이상 또는 선택에 의해 리테일러를 자유 계약.
 *       lew-review-form-spec.md §3.3 Retailer 마스터를 따른다.</li>
 * </ul>
 */
public enum ConsumerType {
    NON_CONTESTABLE,
    CONTESTABLE
}
