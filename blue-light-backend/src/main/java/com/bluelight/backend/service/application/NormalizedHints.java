package com.bluelight.backend.service.application;

import lombok.Builder;
import lombok.Getter;

/**
 * 검증·정규화 후 DB에 저장 가능한 hint 값 묶음 (LEW Review Form P1.B, 스펙 §5.3).
 *
 * <p>{@code ApplicantHintValidator}가 생성한다. null 필드는 "저장할 hint 없음"을 의미하며,
 * 서비스는 그대로 {@link com.bluelight.backend.domain.application.Application#updateApplicantHints(
 * String, String, String, Integer, String, String, Boolean, Integer)}에 전달한다.</p>
 *
 * <p>MSSL 평문은 이 DTO에 포함되지 않는다 — enc/hmac/last4 세 필드로만 분리되어 전달된다
 * (평문이 서비스/엔티티 속성에 잔존하는 것을 방지).</p>
 */
@Getter
@Builder
public class NormalizedHints {

    /** MSSL 전체 평문의 AES-256-GCM 암호문 (v1:BASE64...). */
    private final String msslEnc;
    /** MSSL 전체 평문의 HMAC-SHA256 해시 (64자 hex). */
    private final String msslHmac;
    /** MSSL 뒤 4자리 평문. */
    private final String msslLast4;

    /** 공급 전압(V). */
    private final Integer supplyVoltage;
    /** Consumer Type enum 문자열. */
    private final String consumerType;
    /** Retailer enum 문자열. */
    private final String retailer;
    /** 발전기 보유 여부. */
    private final Boolean hasGenerator;
    /** 발전기 용량(kVA). */
    private final Integer generatorCapacity;
}
