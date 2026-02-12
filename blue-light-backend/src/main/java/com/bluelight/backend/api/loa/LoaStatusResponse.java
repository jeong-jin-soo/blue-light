package com.bluelight.backend.api.loa;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * LOA 상태 응답 DTO
 */
@Getter
@Builder
public class LoaStatusResponse {
    private Long applicationSeq;
    private boolean loaGenerated;
    private boolean loaSigned;
    private LocalDateTime loaSignedAt;
    private Long loaFileSeq;
    private String applicationType;
}
