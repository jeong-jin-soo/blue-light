package com.bluelight.backend.api.docnumber;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.docnumber.DocumentNumberSequence;
import com.bluelight.backend.domain.docnumber.DocumentNumberSequenceRepository;
import com.bluelight.backend.domain.docnumber.DocumentNumberType;
import com.bluelight.backend.domain.docnumber.DocumentNumberTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공통 문서번호 생성 서비스.
 *
 * <p>형식: {@code LK-{DOC_PREFIX}-YYYYMMDD-NNNN} (예: {@code LK-RCP-20260423-0001})</p>
 *
 * <p>스펙: {@code doc/Project Analysis/document-number-generator-spec.md}.</p>
 *
 * <h2>동시성 제어</h2>
 * {@link DocumentNumberSequenceRepository#findByIdForUpdate}가 row-level 비관적 쓰기 락을 걸어
 * 같은 (타입, 날짜) 조합에 대한 동시 발번을 직렬화한다. 재시도 루프 불필요.
 *
 * <h2>트랜잭션 전파</h2>
 * 기본 전파는 {@link Propagation#REQUIRED} — 호출자 트랜잭션이 롤백되면 {@code next_value}
 * 증가도 함께 롤백된다 (Q5 확정, Spec §5.4). "구멍 없는" 연속 번호 유지가 목적.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    // 설정 우선 원칙 예외: 회사 고정 브랜드 (spec §10.1)
    // "LK" 변경이 필요하면 전사 번호 체계 마이그레이션을 동반해야 하므로 의도적으로 코드 상수로 고정.
    private static final String PRIMARY_PREFIX = "LK";

    private static final int MAX_SEQ = 9999;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** SG 기준 발행일을 결정하기 위한 타임존. */
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("^(LK)-([A-Z]{2,5})-([0-9]{8})-([0-9]{4})$");

    private final DocumentNumberTypeRepository typeRepository;
    private final DocumentNumberSequenceRepository sequenceRepository;
    private final Clock clock;

    /**
     * 주어진 문서 타입에 대해 다음 문서번호를 발번.
     *
     * @param docTypeCode {@code document_number_types.code} (예: "RECEIPT")
     * @return 완성된 문서번호 문자열 (예: {@code LK-RCP-20260423-0001})
     * @throws BusinessException
     *         {@code DOC_TYPE_NOT_FOUND} — 존재하지 않거나 비활성·삭제된 타입,
     *         {@code DOC_NUMBER_OVERFLOW} — 해당 날짜 시퀀스가 9999 초과
     */
    @Transactional
    public String generate(String docTypeCode) {
        return generate(docTypeCode, null);
    }

    /**
     * {@link #generate(String)} 오버로드 — 발번 이력을 남기기 위해 요청자 userSeq도 함께 기록.
     */
    @Transactional
    public String generate(String docTypeCode, Long issuedByUserSeq) {
        if (docTypeCode == null || docTypeCode.isBlank()) {
            throw new BusinessException(
                    "docTypeCode must not be blank",
                    HttpStatus.BAD_REQUEST,
                    "DOC_TYPE_CODE_BLANK");
        }

        DocumentNumberType type = typeRepository.findByCodeAndActiveTrue(docTypeCode)
                .orElseThrow(() -> new BusinessException(
                        "Document type not found or inactive: " + docTypeCode,
                        HttpStatus.NOT_FOUND,
                        "DOC_TYPE_NOT_FOUND"));

        LocalDate today = LocalDate.now(clock.withZone(SG_ZONE));

        // 1) 시퀀스 row를 비관적 락으로 확보. 없으면 insert 시도 후 락을 다시 획득.
        //    동시 insert 경쟁이 발생하면 PK 유니크 충돌 → 다른 트랜잭션이 먼저 만든 row를 FOR UPDATE로 재조회.
        DocumentNumberSequence seq = acquireSequenceRow(docTypeCode, today);

        // 2) 오버플로 검사.
        int currentValue = seq.getNextValue();
        if (currentValue > MAX_SEQ) {
            throw new BusinessException(
                    "Daily sequence exceeded " + MAX_SEQ + " for " + docTypeCode + " on " + today,
                    HttpStatus.CONFLICT,
                    "DOC_NUMBER_OVERFLOW");
        }

        // 3) 카운터 +1 (락은 트랜잭션 커밋·롤백 시점까지 유지).
        int issuedValue = seq.advance(issuedByUserSeq);

        // 4) 번호 조립.
        String formatted = String.format("%s-%s-%s-%04d",
                PRIMARY_PREFIX, type.getPrefix(), today.format(DATE_FMT), issuedValue);

        log.debug("Generated document number: type={}, number={}", docTypeCode, formatted);
        return formatted;
    }

    /**
     * 비관적 락으로 시퀀스 row를 확보.
     *
     * <p>먼저 {@code INSERT ... ON DUPLICATE KEY UPDATE} 로 row가 없으면 생성한다.
     * 미존재 row에 대한 {@code SELECT FOR UPDATE} 는 MySQL InnoDB에서 gap lock을 유발해
     * 동시 INSERT끼리 데드락을 일으키므로, 선행 upsert로 row 존재를 보장한 뒤
     * {@code FOR UPDATE}로 락을 건다.</p>
     */
    private DocumentNumberSequence acquireSequenceRow(String docTypeCode, LocalDate today) {
        sequenceRepository.upsertIfMissing(docTypeCode, today);

        return sequenceRepository.findByIdForUpdate(docTypeCode, today)
                .orElseThrow(() -> new BusinessException(
                        "Sequence row creation failed for " + docTypeCode + "/" + today,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "DOC_NUMBER_SEQUENCE_ERROR"));
    }

    /** 문서번호가 유효한 형식인지 검사 (정규식 기반 — DB 조회 없음). */
    public boolean isValid(String documentNumber) {
        return documentNumber != null && NUMBER_PATTERN.matcher(documentNumber).matches();
    }

    /**
     * 문서번호를 파싱. 형식이 올바르지 않으면 {@code Optional.empty()}.
     * 타입 코드 → prefix 매핑은 여기서 검증하지 않으며, 단순히 구조적으로 파싱만 수행.
     */
    public Optional<ParsedDocumentNumber> parse(String documentNumber) {
        if (documentNumber == null) {
            return Optional.empty();
        }
        Matcher m = NUMBER_PATTERN.matcher(documentNumber);
        if (!m.matches()) {
            return Optional.empty();
        }
        String primary = m.group(1);
        String docPrefix = m.group(2);
        String datePart = m.group(3);
        int sequence = Integer.parseInt(m.group(4));
        LocalDate date = LocalDate.parse(datePart, DATE_FMT);
        return Optional.of(new ParsedDocumentNumber(primary, docPrefix, date, sequence));
    }
}
