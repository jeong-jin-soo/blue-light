package com.bluelight.backend.api.invoice;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * E-Invoice 번호 생성기 (invoice-spec §6).
 *
 * <p>형식: {@code {prefix}{yyyyMMdd}{nnn}} — 예: {@code IN20260422001}.</p>
 *
 * <p>일별 시퀀스는 {@link InvoiceRepository#countByInvoiceNumberStartingWith(String)} 로
 * 채번하고, UNIQUE 제약 충돌 시 최대 5회 재시도한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    private static final String DEFAULT_PREFIX = "IN";
    private static final int MAX_RETRIES = 5;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final InvoiceRepository invoiceRepository;
    private final SystemSettingRepository systemSettingRepository;

    /**
     * 주어진 날짜 기준 다음 영수증 번호 발번.
     * <p>동시 발급으로 UNIQUE 충돌이 발생하면 seq+1 증가 후 최대 5회까지 재시도한다.</p>
     */
    public String next(LocalDate date) {
        String prefix = resolvePrefix();
        String datePart = date.format(DATE_FORMAT);
        String combinedPrefix = prefix + datePart;

        long baseCount = invoiceRepository.countByInvoiceNumberStartingWith(combinedPrefix);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            long seq = baseCount + 1 + attempt;
            String candidate = combinedPrefix + String.format("%03d", seq);
            if (!invoiceRepository.existsByInvoiceNumber(candidate)) {
                return candidate;
            }
            log.warn("Invoice number collision on attempt {}: {}", attempt + 1, candidate);
        }

        throw new BusinessException(
                "Failed to generate unique invoice number after " + MAX_RETRIES + " attempts",
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INVOICE_NUMBER_COLLISION");
    }

    private String resolvePrefix() {
        return systemSettingRepository.findById("invoice_number_prefix")
                .map(SystemSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(DEFAULT_PREFIX);
    }
}
