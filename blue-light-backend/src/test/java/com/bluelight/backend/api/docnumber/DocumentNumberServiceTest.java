package com.bluelight.backend.api.docnumber;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.docnumber.DocumentNumberSequence;
import com.bluelight.backend.domain.docnumber.DocumentNumberSequenceRepository;
import com.bluelight.backend.domain.docnumber.DocumentNumberType;
import com.bluelight.backend.domain.docnumber.DocumentNumberTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DocumentNumberService 단위 테스트 (Mockito).
 *
 * <p>통합 테스트(실제 DB + 동시성)는 {@code DocumentNumberServiceIntegrationTest}에 둠.</p>
 *
 * <p>검증 대상: AC-1(포맷), AC-4(날짜 리셋), AC-5(SG 타임존), AC-7(오버플로), AC-8(비활성 타입),
 * AC-10(설정 우선 준수 — 하드코딩된 prefix 없음 → 위임 검증).</p>
 */
@DisplayName("DocumentNumberService - 단위 테스트 (AC-1/4/5/7/8)")
class DocumentNumberServiceTest {

    private DocumentNumberTypeRepository typeRepository;
    private DocumentNumberSequenceRepository sequenceRepository;
    private DocumentNumberService service;

    /** 고정 시각: 2026-04-23 03:00 SGT (= 2026-04-22 19:00 UTC). */
    private static final Instant FIXED_INSTANT_SG_20260423 =
            LocalDate.of(2026, 4, 23).atStartOfDay(ZoneId.of("Asia/Singapore"))
                    .plusHours(3).toInstant();

    @BeforeEach
    void setUp() {
        typeRepository = mock(DocumentNumberTypeRepository.class);
        sequenceRepository = mock(DocumentNumberSequenceRepository.class);
        Clock fixedClock = Clock.fixed(FIXED_INSTANT_SG_20260423, ZoneOffset.UTC);
        service = new DocumentNumberService(typeRepository, sequenceRepository, fixedClock);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private DocumentNumberType activeType(String code, String prefix) {
        return DocumentNumberType.builder()
                .code(code).prefix(prefix)
                .labelKo("영수증").labelEn("Receipt")
                .description("Test").active(true).displayOrder(10)
                .build();
    }

    private void stubTypeActive(String code, String prefix) {
        when(typeRepository.findByCodeAndActiveTrue(code))
                .thenReturn(Optional.of(activeType(code, prefix)));
    }

    private DocumentNumberSequence sequenceAt(String code, LocalDate date, int nextValue) {
        DocumentNumberSequence seq = DocumentNumberSequence.firstOf(code, date);
        // nextValue=1로 초기화된 후 필요한 만큼 advance
        for (int i = 1; i < nextValue; i++) {
            seq.advance(null);
        }
        return seq;
    }

    // ── AC-1: 포맷 정확성 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldReturnFirstSequenceFormattedWhenNoExistingRow")
    void shouldReturnFirstSequenceFormattedWhenNoExistingRow() {
        // AC-1: LK-{prefix}-yyyyMMdd-NNNN 포맷. 첫 발번 → 0001.
        // 서비스는 먼저 upsertIfMissing으로 row 존재를 보장한 뒤 findByIdForUpdate 로 락을 건다.
        stubTypeActive("RECEIPT", "RCP");
        LocalDate today = LocalDate.of(2026, 4, 23);
        when(sequenceRepository.findByIdForUpdate("RECEIPT", today))
                .thenReturn(Optional.of(sequenceAt("RECEIPT", today, 1)));

        String number = service.generate("RECEIPT");

        assertThat(number).isEqualTo("LK-RCP-20260423-0001");
        verify(sequenceRepository).upsertIfMissing("RECEIPT", today);
    }

    @Test
    @DisplayName("shouldAdvanceSequenceWhenRowAlreadyExists")
    void shouldAdvanceSequenceWhenRowAlreadyExists() {
        // AC-1: 기존 시퀀스 3이면 세 번째 발번이 0003.
        stubTypeActive("RECEIPT", "RCP");
        LocalDate today = LocalDate.of(2026, 4, 23);
        DocumentNumberSequence existing = sequenceAt("RECEIPT", today, 3);
        when(sequenceRepository.findByIdForUpdate("RECEIPT", today))
                .thenReturn(Optional.of(existing));

        String number = service.generate("RECEIPT");

        assertThat(number).isEqualTo("LK-RCP-20260423-0003");
        assertThat(existing.getNextValue()).isEqualTo(4);
        verify(sequenceRepository).upsertIfMissing("RECEIPT", today);
    }

    @Test
    @DisplayName("shouldRecordLastIssuedByWhenUserSeqProvided")
    void shouldRecordLastIssuedByWhenUserSeqProvided() {
        stubTypeActive("RECEIPT", "RCP");
        LocalDate today = LocalDate.of(2026, 4, 23);
        DocumentNumberSequence existing = sequenceAt("RECEIPT", today, 1);
        when(sequenceRepository.findByIdForUpdate("RECEIPT", today))
                .thenReturn(Optional.of(existing));

        service.generate("RECEIPT", 42L);

        assertThat(existing.getLastIssuedBy()).isEqualTo(42L);
        assertThat(existing.getLastIssuedAt()).isNotNull();
    }

    // ── AC-8: 비활성 / 미존재 타입 ──────────────────────────────────────────────

    @Test
    @DisplayName("shouldThrowDOC_TYPE_NOT_FOUNDWhenTypeInactive")
    void shouldThrowDOC_TYPE_NOT_FOUNDWhenTypeInactive() {
        when(typeRepository.findByCodeAndActiveTrue("RECEIPT"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generate("RECEIPT"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("DOC_TYPE_NOT_FOUND");
                    assertThat(be.getStatus().value()).isEqualTo(404);
                });

        verify(sequenceRepository, never()).findByIdForUpdate(any(), any());
    }

    @Test
    @DisplayName("shouldThrowDOC_TYPE_CODE_BLANKWhenCodeIsBlank")
    void shouldThrowDOC_TYPE_CODE_BLANKWhenCodeIsBlank() {
        assertThatThrownBy(() -> service.generate("  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("DOC_TYPE_CODE_BLANK"));

        assertThatThrownBy(() -> service.generate((String) null))
                .isInstanceOf(BusinessException.class);
    }

    // ── AC-7: 오버플로 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldThrowDOC_NUMBER_OVERFLOWWhenSequenceExceeds9999")
    void shouldThrowDOC_NUMBER_OVERFLOWWhenSequenceExceeds9999() {
        stubTypeActive("RECEIPT", "RCP");
        LocalDate today = LocalDate.of(2026, 4, 23);

        // nextValue가 이미 10000인 상황을 시뮬레이션 (reflection 대신 advance 반복 — 비용 크므로 직접 객체 스텁)
        DocumentNumberSequence overflowed = sequenceAt("RECEIPT", today, 1);
        // advance를 9999번 반복하여 nextValue = 10000 로 도달
        for (int i = 0; i < 9999; i++) {
            overflowed.advance(null);
        }
        assertThat(overflowed.getNextValue()).isEqualTo(10000);

        when(sequenceRepository.findByIdForUpdate("RECEIPT", today))
                .thenReturn(Optional.of(overflowed));

        assertThatThrownBy(() -> service.generate("RECEIPT"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("DOC_NUMBER_OVERFLOW");
                    assertThat(be.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    @DisplayName("shouldGenerateSuccessfullyWhenSequenceIs9999")
    void shouldGenerateSuccessfullyWhenSequenceIs9999() {
        // 경계값: nextValue=9999이면 LK-RCP-...-9999 생성, 이후 nextValue=10000으로 오버플로.
        stubTypeActive("RECEIPT", "RCP");
        LocalDate today = LocalDate.of(2026, 4, 23);
        DocumentNumberSequence atBoundary = sequenceAt("RECEIPT", today, 1);
        for (int i = 0; i < 9998; i++) {
            atBoundary.advance(null);
        }
        assertThat(atBoundary.getNextValue()).isEqualTo(9999);
        when(sequenceRepository.findByIdForUpdate("RECEIPT", today))
                .thenReturn(Optional.of(atBoundary));

        String number = service.generate("RECEIPT");

        assertThat(number).isEqualTo("LK-RCP-20260423-9999");
    }

    // ── AC-5: SG 타임존 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldUseAsiaSingaporeDateEvenWhenClockIsUTC")
    void shouldUseAsiaSingaporeDateEvenWhenClockIsUTC() {
        // Clock를 "UTC 2026-04-22 19:00" 으로 고정 → SG 기준은 2026-04-23 03:00.
        // generate 호출 시 issue_date는 SG 기준(2026-04-23)이어야 함.
        stubTypeActive("RECEIPT", "RCP");
        LocalDate sgToday = LocalDate.of(2026, 4, 23);

        DocumentNumberSequence existing = sequenceAt("RECEIPT", sgToday, 1);
        when(sequenceRepository.findByIdForUpdate(eq("RECEIPT"), eq(sgToday)))
                .thenReturn(Optional.of(existing));

        String number = service.generate("RECEIPT");

        assertThat(number).contains("20260423");
        verify(sequenceRepository).findByIdForUpdate("RECEIPT", sgToday);
    }

    // ── isValid / parse ────────────────────────────────────────────────────────

    @Test
    @DisplayName("isValid_shouldMatchOnlyValidFormat")
    void isValid_shouldMatchOnlyValidFormat() {
        assertThat(service.isValid("LK-RCP-20260423-0001")).isTrue();
        assertThat(service.isValid("LK-INVCE-20260423-0001")).isTrue();   // 5자 prefix
        assertThat(service.isValid("LK-AB-20260423-0001")).isTrue();       // 2자 prefix

        // 실패 케이스
        assertThat(service.isValid(null)).isFalse();
        assertThat(service.isValid("")).isFalse();
        assertThat(service.isValid("IN20260422001")).isFalse();           // 구 형식
        assertThat(service.isValid("lk-rcp-20260423-0001")).isFalse();    // 소문자
        assertThat(service.isValid("LK-RCP-20260423-1")).isFalse();       // 자릿수 부족
        assertThat(service.isValid("LK-RCP-20260423-00001")).isFalse();   // 자릿수 초과
        assertThat(service.isValid("LK-R-20260423-0001")).isFalse();      // prefix 너무 짧음
        assertThat(service.isValid("LK-RCPXYZ-20260423-0001")).isFalse(); // prefix 너무 김
    }

    @Test
    @DisplayName("parse_shouldExtractComponentsFromValidNumber")
    void parse_shouldExtractComponentsFromValidNumber() {
        ParsedDocumentNumber parsed = service.parse("LK-RCP-20260423-0007").orElseThrow();

        assertThat(parsed.primaryPrefix()).isEqualTo("LK");
        assertThat(parsed.docPrefix()).isEqualTo("RCP");
        assertThat(parsed.issueDate()).isEqualTo(LocalDate.of(2026, 4, 23));
        assertThat(parsed.sequence()).isEqualTo(7);
    }

    @Test
    @DisplayName("parse_shouldReturnEmptyForInvalidInput")
    void parse_shouldReturnEmptyForInvalidInput() {
        assertThat(service.parse(null)).isEmpty();
        assertThat(service.parse("IN20260422001")).isEmpty();
        assertThat(service.parse("garbage")).isEmpty();
    }
}
