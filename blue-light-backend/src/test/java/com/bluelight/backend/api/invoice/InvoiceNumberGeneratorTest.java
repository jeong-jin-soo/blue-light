package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.docnumber.DocumentNumberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * InvoiceNumberGenerator — 공통 문서번호 엔진에 올바르게 위임하는지 검증.
 *
 * <p>번호 포맷·충돌 제어·오버플로 등의 실제 로직은 {@code DocumentNumberServiceTest}에서 검증한다.
 * 이 테스트는 Facade의 위임 계약(DOC_TYPE_CODE="RECEIPT" 전달, 반환값 그대로 전파)만 확인한다.</p>
 */
@DisplayName("InvoiceNumberGenerator - 공통 엔진 위임 검증 (Phase 1.4 리팩터링 후)")
class InvoiceNumberGeneratorTest {

    private DocumentNumberService documentNumberService;
    private InvoiceNumberGenerator generator;

    @BeforeEach
    void setUp() {
        documentNumberService = mock(DocumentNumberService.class);
        generator = new InvoiceNumberGenerator(documentNumberService);
    }

    @Test
    @DisplayName("shouldDelegateToDocumentNumberServiceWithReceiptDocTypeCode")
    void shouldDelegateToDocumentNumberServiceWithReceiptDocTypeCode() {
        // Given: 공통 엔진이 유효한 번호를 반환
        when(documentNumberService.generate("RECEIPT"))
                .thenReturn("LK-RCP-20260423-0001");

        // When
        String result = generator.next(LocalDate.of(2026, 4, 23));

        // Then: 공통 엔진이 "RECEIPT" 타입 코드로 호출되고, 반환값이 그대로 전파됨
        assertThat(result).isEqualTo("LK-RCP-20260423-0001");
        verify(documentNumberService).generate(eq("RECEIPT"));
        verifyNoMoreInteractions(documentNumberService);
    }

    @Test
    @DisplayName("shouldPassthroughEvenWhenDateArgumentIsNull")
    void shouldPassthroughEvenWhenDateArgumentIsNull() {
        // date 인자는 시그니처 호환용(무시됨). null이어도 정상 위임되어야 함.
        when(documentNumberService.generate("RECEIPT"))
                .thenReturn("LK-RCP-20260423-0002");

        String result = generator.next(null);

        assertThat(result).isEqualTo("LK-RCP-20260423-0002");
        verify(documentNumberService).generate("RECEIPT");
    }

    @Test
    @DisplayName("shouldPropagateExceptionFromDocumentNumberService")
    void shouldPropagateExceptionFromDocumentNumberService() {
        // 공통 엔진에서 발생한 예외는 호출자로 그대로 전파되어야 함.
        RuntimeException underlying = new RuntimeException("overflow");
        when(documentNumberService.generate("RECEIPT")).thenThrow(underlying);

        try {
            generator.next(LocalDate.now());
        } catch (RuntimeException e) {
            assertThat(e).isSameAs(underlying);
        }
    }
}
