package com.bluelight.backend.domain.kva;

/**
 * 견적 조정 원장({@link KvaAdjustmentRecord}) 행의 조정 유형.
 *
 * <ul>
 *   <li>{@link #KVA_CHANGE} — kVA 변경에 따른 견적 조정 (기존 kVA 사후조정 흐름). 기본값.</li>
 *   <li>{@link #SLD_ADDED} — SLD self-upload → LEW 작성 전환에 따른 SLD 작성비 추가
 *       (sld-lew-conversion-fee-spec.md §3.2). previousKva == newKva, amountDifference = +sldFee.</li>
 * </ul>
 *
 * <p>원장은 본래 kVA 전용이었으나 "견적 조정 원장"으로 일반화되었다(테이블/엔티티명은 연속성 위해 유지).</p>
 */
public enum AdjustmentType {
    KVA_CHANGE,
    SLD_ADDED
}
