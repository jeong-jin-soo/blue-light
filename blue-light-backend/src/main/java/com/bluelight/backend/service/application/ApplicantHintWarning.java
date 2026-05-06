package com.bluelight.backend.service.application;

import lombok.Builder;
import lombok.Getter;

/**
 * 신청자 hint 필드에 대한 경고 수준 검증 결과 (LEW Review Form P1.B, 스펙 §5.4).
 *
 * <p>경고는 신청 200 OK를 차단하지 않고 {@code ApplicationResponse.warnings}에
 * 포함되어 반환된다. 클라이언트는 토스트 등으로 표시한 후, LEW finalize 단계에서
 * 엄격 차단이 일어난다.</p>
 */
@Getter
@Builder
public class ApplicantHintWarning {

    /** 어느 필드인가 (예: "msslHint"). */
    private final String field;

    /** 기계 판독 가능한 사유 코드 (예: "INVALID_FORMAT"). */
    private final String code;

    /** 사람이 읽는 사유 설명. */
    private final String reason;
}
