package com.bluelight.backend.api.loa;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * LOA 상태 응답 DTO.
 *
 * <p>교환 모델(loa-exchange-redesign-spec.md §3.3)로 확장:
 * {@code loaStage} + 파일 seq 2종(신청자 서명본 / LEW 최종본) + active 폼 메타를 반환한다.
 * 기존 {@code loaGenerated}/{@code loaSigned}/{@code loaFileSeq}/{@code loaSignedAt} 은 하위호환을 위해 유지.</p>
 */
@Getter
@Builder
public class LoaStatusResponse {
    private Long applicationSeq;
    private String applicationType;

    // ── 교환 모델 (신규) ──
    /** LoA 진행 단계 (NOT_STARTED / FORM_SENT / APPLICANT_UPLOADED / FINAL_UPLOADED). */
    private String loaStage;
    /** 신청자 오프라인 서명본(OWNER_AUTH_LETTER) 최신 파일 seq (없으면 null). */
    private Long applicantFileSeq;
    /** LEW 최종본(LOA_FINAL) 최신 파일 seq (없으면 null). */
    private Long finalFileSeq;
    /** 신청에 적용 가능한 active LoA 폼이 존재하는지 (NEW 전용; RENEWAL 은 false). */
    private boolean activeFormAvailable;
    /** active LoA 폼 라벨 (없으면 null). */
    private String activeFormLabel;

    // ── 레거시 (하위호환 유지) ──
    private boolean loaGenerated;
    private boolean loaSigned;
    private LocalDateTime loaSignedAt;
    private Long loaFileSeq;
}
