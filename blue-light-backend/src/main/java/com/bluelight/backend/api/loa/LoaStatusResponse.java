package com.bluelight.backend.api.loa;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * LOA 상태 응답 DTO.
 *
 * <p>{@code loaStage}(LEW 최종본 트랙) + 파일 seq 2종(신청자 LoA / LEW 최종본)을 반환한다.
 * 신청자 LoA 와 LEW 최종본은 별개 트랙으로 구분된다.
 * 기존 {@code loaGenerated}/{@code loaSigned}/{@code loaFileSeq}/{@code loaSignedAt} 은 하위호환을 위해 유지.</p>
 */
@Getter
@Builder
public class LoaStatusResponse {
    private Long applicationSeq;
    private String applicationType;

    // ── 두 트랙 구분 ──
    /** LoA 단계 — LEW 최종본 트랙 (NOT_STARTED / FINAL_UPLOADED). */
    private String loaStage;
    /** 신청자 LoA(OWNER_AUTH_LETTER) 최신 파일 seq (없으면 null). Documents 트랙. */
    private Long applicantFileSeq;
    /** LEW 최종본(LOA_FINAL) 최신 파일 seq (없으면 null). */
    private Long finalFileSeq;

    // ── 레거시 (하위호환 유지) ──
    private boolean loaGenerated;
    private boolean loaSigned;
    private LocalDateTime loaSignedAt;
    private Long loaFileSeq;
}
