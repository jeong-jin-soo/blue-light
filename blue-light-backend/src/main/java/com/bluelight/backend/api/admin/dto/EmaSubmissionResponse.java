package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.EmaSubmissionStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * EMA 제출 추적 응답 DTO — ema-submission-tracking-spec.md §7.
 *
 * <p>상태 컬럼 + 파일 존재 여부(EMA_ACK / LICENSE_PDF) + 설정값(ema.ack.required) + 서버 계산
 * 필드(canComplete / emaGrandfathered)를 한 번에 내려 LEW/ADMIN UI 가 동선 게이팅·구분 배지를
 * 그릴 수 있게 한다.</p>
 *
 * <p>파일 존재 여부·설정값은 서비스가 주입한다(DTO 가 repository/settings 에 의존하지 않도록
 * 정적 팩토리 {@link #of} 가 계산 결과를 인자로 받음).</p>
 */
@Getter
@Builder
public class EmaSubmissionResponse {

    private EmaSubmissionStatus emaSubmissionStatus;
    private LocalDateTime emaSubmittedAt;
    private String emaReferenceNo;
    private Long emaSubmittedByUserSeq;
    private String emaSubmittedByName;     // 표시용 이름 (서버 join, 미상이면 null)
    private LocalDateTime emaDecisionAt;
    private String emaQueryNote;

    private boolean emaAckPresent;         // EMA_ACK 첨부 존재 여부
    private boolean emaAckRequired;        // = system_settings.ema.ack.required (설정 우선 — UI 필수/선택 라벨)
    private boolean emaGrandfathered;      // 허점#2 — backfill 로 APPROVED 된 legacy 건 식별
    private boolean licensePdfPresent;     // 완료 게이트 사전 안내용
    private boolean canComplete;           // = (APPROVED && licensePdfPresent) 서버 계산

    /**
     * EMA 응답 조립. 파일 존재 여부·설정값·제출자 이름은 서비스에서 계산해 전달한다.
     *
     * @param application      대상 신청 (EMA 상태 컬럼 소스)
     * @param emaAckPresent    EMA_ACK 첨부 존재 여부
     * @param licensePdfPresent LICENSE_PDF 첨부 존재 여부
     * @param emaAckRequired   ema.ack.required 설정값
     * @param submittedByName  제출 actor 표시 이름 (미상이면 null)
     */
    public static EmaSubmissionResponse of(Application application,
                                           boolean emaAckPresent,
                                           boolean licensePdfPresent,
                                           boolean emaAckRequired,
                                           String submittedByName) {
        EmaSubmissionStatus status = application.getEmaSubmissionStatus();
        boolean approved = status == EmaSubmissionStatus.APPROVED;
        // 허점#2: backfill grandfathered = APPROVED 인데 결정시각·접수번호가 둘 다 비어있는 건.
        boolean grandfathered = approved
                && application.getEmaDecisionAt() == null
                && (application.getEmaReferenceNo() == null || application.getEmaReferenceNo().isBlank());
        // canComplete: ema=APPROVED 그리고 LICENSE_PDF 첨부 존재 (App.status==IN_PROGRESS 는 게이트가 별도 검증).
        boolean canComplete = approved && licensePdfPresent
                && application.getStatus() == ApplicationStatus.IN_PROGRESS;

        return EmaSubmissionResponse.builder()
                .emaSubmissionStatus(status)
                .emaSubmittedAt(application.getEmaSubmittedAt())
                .emaReferenceNo(application.getEmaReferenceNo())
                .emaSubmittedByUserSeq(application.getEmaSubmittedByUserSeq())
                .emaSubmittedByName(submittedByName)
                .emaDecisionAt(application.getEmaDecisionAt())
                .emaQueryNote(application.getEmaQueryNote())
                .emaAckPresent(emaAckPresent)
                .emaAckRequired(emaAckRequired)
                .emaGrandfathered(grandfathered)
                .licensePdfPresent(licensePdfPresent)
                .canComplete(canComplete)
                .build();
    }
}
