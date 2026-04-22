package com.bluelight.backend.api.invoice;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AC-6 & AC-15: 영수증 번호 생성 규칙 + 충돌 재시도 로직 검증.
 */
@DisplayName("InvoiceNumberGenerator - AC-6/AC-15")
class InvoiceNumberGeneratorTest {

    private InvoiceRepository invoiceRepository;
    private SystemSettingRepository systemSettingRepository;
    private InvoiceNumberGenerator generator;

    private static final LocalDate TEST_DATE = LocalDate.of(2026, 4, 22);
    private static final String DATE_PART = "20260422";

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        systemSettingRepository = mock(SystemSettingRepository.class);

        // 기본: prefix 설정 없음 → DEFAULT_PREFIX "IN" 사용
        when(systemSettingRepository.findById("invoice_number_prefix"))
                .thenReturn(Optional.empty());

        generator = new InvoiceNumberGenerator(invoiceRepository, systemSettingRepository);
    }

    // ── AC-6: 형식 IN + yyyyMMdd + 3자리 순번 ────────────────────────────────

    @Test
    @DisplayName("shouldReturnIN001WhenNoPreviousInvoicesExist")
    void shouldReturnIN001WhenNoPreviousInvoicesExist() {
        // AC-6: 당일 첫 번째 인보이스 → IN20260422001
        // Given
        when(invoiceRepository.countByInvoiceNumberStartingWith("IN" + DATE_PART)).thenReturn(0L);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "001")).thenReturn(false);

        // When
        String result = generator.next(TEST_DATE);

        // Then
        assertThat(result).isEqualTo("IN20260422001");
    }

    @Test
    @DisplayName("shouldReturn003WhenTwoPreviousInvoicesExist")
    void shouldReturn003WhenTwoPreviousInvoicesExist() {
        // AC-6: 기존 2건이면 세 번째 → IN20260422003
        // Given
        when(invoiceRepository.countByInvoiceNumberStartingWith("IN" + DATE_PART)).thenReturn(2L);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "003")).thenReturn(false);

        // When
        String result = generator.next(TEST_DATE);

        // Then
        assertThat(result).isEqualTo("IN20260422003");
    }

    @Test
    @DisplayName("shouldUseCustomPrefixWhenConfigured")
    void shouldUseCustomPrefixWhenConfigured() {
        // AC-6: invoice_number_prefix 설정값이 있으면 그 값 사용
        // Given
        SystemSetting prefixSetting = new SystemSetting("invoice_number_prefix", "REC", "custom prefix");
        when(systemSettingRepository.findById("invoice_number_prefix"))
                .thenReturn(Optional.of(prefixSetting));
        when(invoiceRepository.countByInvoiceNumberStartingWith("REC" + DATE_PART)).thenReturn(0L);
        when(invoiceRepository.existsByInvoiceNumber("REC" + DATE_PART + "001")).thenReturn(false);

        // When
        String result = generator.next(TEST_DATE);

        // Then
        assertThat(result).startsWith("REC").contains(DATE_PART).endsWith("001");
    }

    // ── AC-15: 충돌 재시도 ────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldReturnIN005WhenFirst4AttemptsCollide")
    void shouldReturnIN005WhenFirst4AttemptsCollide() {
        // AC-15: existsByInvoiceNumber 처음 4번 true, 5번째 false → IN{date}005 반환
        // Given: countByInvoiceNumberStartingWith가 0 → 첫 시도는 seq=1
        // 충돌로 attempt 0 → seq=1, attempt 1 → seq=2, ... attempt 4 → seq=5
        when(invoiceRepository.countByInvoiceNumberStartingWith("IN" + DATE_PART)).thenReturn(0L);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "001")).thenReturn(true);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "002")).thenReturn(true);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "003")).thenReturn(true);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "004")).thenReturn(true);
        when(invoiceRepository.existsByInvoiceNumber("IN" + DATE_PART + "005")).thenReturn(false);

        // When
        String result = generator.next(TEST_DATE);

        // Then
        assertThat(result).contains("005");
        assertThat(result).isEqualTo("IN" + DATE_PART + "005");
    }

    @Test
    @DisplayName("shouldThrowINVOICE_NUMBER_COLLISIONWhenAll5AttemptsCollide")
    void shouldThrowINVOICE_NUMBER_COLLISIONWhenAll5AttemptsCollide() {
        // AC-15: 5회 모두 true → INVOICE_NUMBER_COLLISION 500 예외
        // Given
        when(invoiceRepository.countByInvoiceNumberStartingWith("IN" + DATE_PART)).thenReturn(0L);
        when(invoiceRepository.existsByInvoiceNumber(anyString())).thenReturn(true);

        // When / Then
        assertThatThrownBy(() -> generator.next(TEST_DATE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVOICE_NUMBER_COLLISION"))
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value())
                        .isEqualTo(500));
    }
}
