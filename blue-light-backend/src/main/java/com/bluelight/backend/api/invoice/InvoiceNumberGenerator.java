package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.docnumber.DocumentNumberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * E-Invoice 번호 생성기 — 공통 문서번호 엔진({@link DocumentNumberService})에 위임하는 Facade.
 *
 * <p>Phase 1.4 리팩터링 결과물. 구 형식 {@code IN20260422001}은 더 이상 생성하지 않으며,
 * 신규 발행분은 공통 형식 {@code LK-RCP-YYYYMMDD-NNNN}으로 발번된다. 스펙:
 * {@code doc/Project Analysis/document-number-generator-spec.md §9}.</p>
 *
 * <p>Phase 2에서 이 클래스는 제거되고, 호출자({@code InvoiceGenerationService})는
 * {@link DocumentNumberService}를 직접 주입받게 된다.</p>
 *
 * @deprecated 2026-04: Phase 2에서 제거 예정. 신규 코드는 {@link DocumentNumberService}를 직접 사용할 것.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated(since = "2026-04", forRemoval = true)
public class InvoiceNumberGenerator {

    /** 영수증 문서 타입 코드 — {@code document_number_types.code}. */
    static final String DOC_TYPE_CODE = "RECEIPT";

    private final DocumentNumberService documentNumberService;

    /**
     * 영수증 번호 발번.
     *
     * @param date (무시됨) 하위 호환을 위해 시그니처만 유지. 실제 발행일은 공통 엔진이
     *             Asia/Singapore 기준으로 자체 결정 (스펙 §7). {@code null} 허용.
     * @return {@code LK-RCP-YYYYMMDD-NNNN} 형식의 문서번호.
     */
    public String next(LocalDate date) {
        return documentNumberService.generate(DOC_TYPE_CODE);
    }
}
