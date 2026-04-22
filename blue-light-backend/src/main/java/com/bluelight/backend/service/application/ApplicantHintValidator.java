package com.bluelight.backend.service.application;

import com.bluelight.backend.common.crypto.FieldEncryptionUtil;
import com.bluelight.backend.common.crypto.HmacUtil;
import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 신청자 hint 필드에 대한 경고 수준 검증 + 정규화 (LEW Review Form P1.B, 스펙 §5.4).
 *
 * <p><b>핵심 원칙 — 경고만, 신청은 절대 차단하지 않는다</b>:
 * <ul>
 *   <li>형식 오류 → 해당 필드는 저장 생략 + warning 반환.</li>
 *   <li>허용 범위 밖 값 → 저장 생략 + warning.</li>
 *   <li>{@code hasGenerator=true & capacity=null}은 저장은 허용하되 warning 첨부
 *       — LEW finalize 단계에서만 엄격 차단 (스펙 §3.3, §9-6).</li>
 * </ul>
 * 모든 분기에서 신청 자체는 200 OK / 201 Created를 유지한다.</p>
 *
 * <p>MSSL 평문은 이 메서드 안에서만 다루고, 결과 DTO에는 enc/hmac/last4로 분리되어 담긴다
 * — 평문이 상위 레이어로 새지 않도록 하기 위함.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicantHintValidator {

    /** MSSL Account No 정규식 (스펙 §3.2). */
    private static final Pattern MSSL_PATTERN = Pattern.compile("^\\d{3}-\\d{2}-\\d{4}-\\d$");

    /** 허용 전압 집합 (스펙 §2.1 CHECK 제약과 동일). */
    private static final Set<Integer> ALLOWED_VOLTAGES = Set.of(230, 400, 6600, 22000);

    private final FieldEncryptionUtil fieldEncryptionUtil;
    private final HmacUtil hmacUtil;

    /**
     * 6종 hint 입력을 검증·정규화한다. 결과에는 저장 가능한 값과 경고 목록이 함께 담긴다.
     *
     * @param msslHintPlain         MSSL Account No 평문 (예: "123-45-6789-0")
     * @param supplyVoltageHint     공급 전압 hint (V)
     * @param consumerTypeHintRaw   Consumer Type enum 문자열
     * @param retailerHintRaw       Retailer enum 문자열
     * @param hasGeneratorHint      발전기 보유 hint
     * @param generatorCapacityHint 발전기 용량 hint (kVA)
     */
    public ApplicantHintValidationResult validateAndNormalize(
            String msslHintPlain,
            Integer supplyVoltageHint,
            String consumerTypeHintRaw,
            String retailerHintRaw,
            Boolean hasGeneratorHint,
            Integer generatorCapacityHint) {

        List<ApplicantHintWarning> warnings = new ArrayList<>();
        NormalizedHints.NormalizedHintsBuilder builder = NormalizedHints.builder();

        // ── MSSL ──
        String msslTrimmed = trimToNull(msslHintPlain);
        if (msslTrimmed != null) {
            if (!MSSL_PATTERN.matcher(msslTrimmed).matches()) {
                warnings.add(warning("msslHint", "INVALID_FORMAT",
                        "MSSL Account No must match format ###-##-####-# (e.g. 123-45-6789-0)"));
            } else {
                builder.msslEnc(fieldEncryptionUtil.encrypt(msslTrimmed));
                builder.msslHmac(hmacUtil.hmac(msslTrimmed));
                builder.msslLast4(extractLast4(msslTrimmed));
            }
        }

        // ── Supply Voltage ──
        if (supplyVoltageHint != null) {
            if (!ALLOWED_VOLTAGES.contains(supplyVoltageHint)) {
                warnings.add(warning("supplyVoltageHint", "INVALID_VALUE",
                        "Supply voltage must be one of 230, 400, 6600, 22000"));
            } else {
                builder.supplyVoltage(supplyVoltageHint);
            }
        }

        // ── Consumer Type ──
        String consumerTypeTrimmed = trimToNull(consumerTypeHintRaw);
        if (consumerTypeTrimmed != null) {
            try {
                ConsumerType parsed = ConsumerType.valueOf(consumerTypeTrimmed);
                builder.consumerType(parsed.name());
            } catch (IllegalArgumentException e) {
                warnings.add(warning("consumerTypeHint", "INVALID_VALUE",
                        "Consumer type must be NON_CONTESTABLE or CONTESTABLE"));
            }
        }

        // ── Retailer ──
        String retailerTrimmed = trimToNull(retailerHintRaw);
        if (retailerTrimmed != null) {
            try {
                RetailerCode parsed = RetailerCode.valueOf(retailerTrimmed);
                builder.retailer(parsed.name());
            } catch (IllegalArgumentException e) {
                warnings.add(warning("retailerHint", "INVALID_VALUE",
                        "Retailer code is not recognised"));
            }
        }

        // ── Generator flag + capacity ──
        if (hasGeneratorHint != null) {
            builder.hasGenerator(hasGeneratorHint);
            if (Boolean.TRUE.equals(hasGeneratorHint) && generatorCapacityHint == null) {
                // 저장은 허용(hasGenerator=true만), LEW finalize에서 엄격 차단.
                warnings.add(warning("generatorCapacityHint", "MISSING_WHEN_HAS_GENERATOR",
                        "Generator capacity is recommended when a generator is present"));
            }
        }
        if (generatorCapacityHint != null) {
            if (generatorCapacityHint <= 0) {
                warnings.add(warning("generatorCapacityHint", "INVALID_VALUE",
                        "Generator capacity must be positive"));
            } else {
                builder.generatorCapacity(generatorCapacityHint);
            }
        }

        return ApplicantHintValidationResult.builder()
                .normalized(builder.build())
                .warnings(warnings)
                .build();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String extractLast4(String mssl) {
        // MSSL 포맷 "###-##-####-#" — "-"로 분리한 마지막 조각은 1자리 체크 디지트.
        // 앞 부분의 끝 3자리 + 체크 디지트 = 4자리로 last4 구성.
        int len = mssl.length();
        StringBuilder digits = new StringBuilder();
        for (int i = len - 1; i >= 0 && digits.length() < 4; i--) {
            char c = mssl.charAt(i);
            if (Character.isDigit(c)) {
                digits.insert(0, c);
            }
        }
        return digits.length() == 4 ? digits.toString() : null;
    }

    private static ApplicantHintWarning warning(String field, String code, String reason) {
        return ApplicantHintWarning.builder()
                .field(field)
                .code(code)
                .reason(reason)
                .build();
    }
}
