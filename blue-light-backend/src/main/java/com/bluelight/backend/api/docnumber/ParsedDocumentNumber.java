package com.bluelight.backend.api.docnumber;

import java.time.LocalDate;

/**
 * 문서번호 파싱 결과. 감사·디버깅·UI 툴팁 등에서 사용.
 * 예: {@code LK-RCP-20260423-0001} →
 *     {@code ParsedDocumentNumber("LK", "RCP", 2026-04-23, 1)}
 */
public record ParsedDocumentNumber(
        String primaryPrefix,
        String docPrefix,
        LocalDate issueDate,
        int sequence
) {}
