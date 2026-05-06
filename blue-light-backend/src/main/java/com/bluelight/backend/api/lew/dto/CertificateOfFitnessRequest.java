package com.bluelight.backend.api.lew.dto;

import com.bluelight.backend.domain.cof.ConsumerType;
import com.bluelight.backend.domain.cof.RetailerCode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * LEW Review Form — CoF 10 필드 입력 DTO (lew-review-form-spec.md §3.2).
 *
 * <p>Draft Save / Finalize 양쪽에서 동일 본문을 사용한다. 필드는 전부 {@code nullable}이고
 * Draft Save는 부분 입력 허용, Finalize는 서비스 레이어가 필수 필드 전수 재검증한다
 * (AC §9-6, §9-7, §9-9).</p>
 *
 * <p>주의: {@code certifiedByLew}, {@code certifiedAt}, {@code version} 3종은 이 DTO에 포함되지 않는다
 * — 서버 전용 기록. 버전 충돌 방지는 낙관적 락({@code @Version})이 담당.</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CertificateOfFitnessRequest {

    /** MSSL Account No 평문 — 저장 시 enc/hmac/last4로 분리. Draft에서는 nullable. */
    @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{4}-\\d$",
            message = "MSSL Account No must match format ###-##-####-#")
    private String msslAccountNo;

    /** 소비자 유형 (NON_CONTESTABLE | CONTESTABLE). */
    private ConsumerType consumerType;

    /** 리테일러 코드. Contestable 시 필수는 서비스 finalize에서 검증. */
    private RetailerCode retailerCode;

    /** 공급 전압(V). 230/400/6600/22000 — 서비스에서 허용 집합 검증. */
    @Positive(message = "Supply voltage must be positive")
    private Integer supplyVoltageV;

    /** 승인 부하(kVA). */
    @Positive(message = "Approved load kVA must be positive")
    private Integer approvedLoadKva;

    /** 발전기 보유 여부. */
    private Boolean hasGenerator;

    /** 발전기 용량(kVA). hasGenerator=true일 때 서비스가 필수 검증. */
    @Positive(message = "Generator capacity must be positive")
    private Integer generatorCapacityKva;

    /** 점검 주기(개월): 6/12/24/36/60 — 서비스에서 허용 집합 검증. */
    @Positive(message = "Inspection interval must be positive")
    private Integer inspectionIntervalMonths;

    /** LEW 선임일 (기본값 오늘 — 서비스에서 null이면 today 대입). */
    private LocalDate lewAppointmentDate;

    /** LEW 동의일 (finalize 시 null이면 today로 자동). */
    private LocalDate lewConsentDate;
}
