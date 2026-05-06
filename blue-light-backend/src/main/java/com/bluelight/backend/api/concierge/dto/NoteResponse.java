package com.bluelight.backend.api.concierge.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ConciergeNote 응답 DTO (★ Phase 1 PR#4 Stage A).
 */
@Getter
@Builder
public class NoteResponse {

    private Long conciergeNoteSeq;
    private Long authorUserSeq;
    private String authorName;
    /** NoteChannel (PHONE/EMAIL/WHATSAPP/IN_PERSON/OTHER) name */
    private String channel;
    private String content;
    private LocalDateTime createdAt;
}
