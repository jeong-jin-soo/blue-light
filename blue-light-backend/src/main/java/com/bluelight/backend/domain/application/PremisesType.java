package com.bluelight.backend.domain.application;

/**
 * 설치 장소(Premises) 유형 — EMA ELISE 필드 기반 분류
 *
 * EMA ELISE는 Installation Premises Type을 요구하며, 본 enum은
 * `doc/Project Analysis/ema-field-jit-plan.md`의 필드 매핑을 따른다.
 * 문자열 저장은 VARCHAR(30)로 이뤄진다.
 */
public enum PremisesType {
    COMMERCIAL,
    FACTORIES,
    FARM,
    RESIDENTIAL,
    INDUSTRIAL,
    HOTEL,
    HEALTHCARE,
    EDUCATION,
    GOVERNMENT,
    MIXED_USE,
    OTHER
}
