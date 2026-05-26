package com.bluelight.backend.domain.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NotificationTemplateDraft 상태 전이 단위 테스트 (PR-T1, D-1 2-step publish).
 *
 * <p>PENDING → APPROVED / REJECTED / WITHDRAWN 상태머신 가드와 reject reviewNote 필수 검증.</p>
 */
@DisplayName("NotificationTemplateDraft - PR-T1")
class NotificationTemplateDraftTest {

    private NotificationTemplateDraft buildDraft() {
        return NotificationTemplateDraft.builder()
                .templateSeq(42L)
                .templateCode("A-17")
                .channel(NotificationChannel.EMAIL)
                .locale("en")
                .subject("[LicenseKaki] Payment requested · #{{publicCode}}")
                .bodyText("Hi {{applicantName}}, please pay SGD {{amount}}.")
                .variablesJson("[\"applicantName\",\"amount\",\"publicCode\"]")
                .providerTemplateName(null)
                .category(NotificationCategory.PAYMENT)
                .severity(NotificationSeverity.CRITICAL)
                .recipientRoles("APPLICANT")
                .submittedBy(1001L)
                .submissionNote("법무팀 요청 — opt-out 링크 위치 변경")
                .build();
    }

    @Test
    @DisplayName("빌더 - 기본 상태 PENDING, submittedAt 자동 설정")
    void builder_defaultsToPending() {
        NotificationTemplateDraft draft = buildDraft();

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.PENDING);
        assertThat(draft.getSubmittedAt()).isNotNull();
        assertThat(draft.getReviewedBy()).isNull();
        assertThat(draft.getReviewedAt()).isNull();
        assertThat(draft.getCategory()).isEqualTo(NotificationCategory.PAYMENT);
    }

    @Test
    @DisplayName("edit - PENDING 상태에서 본문/메타 수정 가능")
    void edit_modifiesContentWhilePending() {
        NotificationTemplateDraft draft = buildDraft();

        draft.edit(
                "[LicenseKaki] Payment requested (revised) · #{{publicCode}}",
                "Hi {{applicantName}}, please settle SGD {{amount}} by {{deadline}}.",
                "[\"applicantName\",\"amount\",\"publicCode\",\"deadline\"]",
                null,
                NotificationCategory.PAYMENT,
                NotificationSeverity.CRITICAL,
                "APPLICANT",
                "리뷰어 피드백 반영 — deadline 추가"
        );

        assertThat(draft.getSubject()).contains("(revised)");
        assertThat(draft.getBodyText()).contains("{{deadline}}");
        assertThat(draft.getVariablesJson()).contains("deadline");
        assertThat(draft.getSubmissionNote()).contains("리뷰어 피드백 반영");
        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.PENDING);
    }

    @Test
    @DisplayName("approve - APPROVED 상태, reviewer/reviewedAt 기록")
    void approve_transitionsToApproved() {
        NotificationTemplateDraft draft = buildDraft();

        draft.approve(9001L, "LGTM");

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.APPROVED);
        assertThat(draft.getReviewedBy()).isEqualTo(9001L);
        assertThat(draft.getReviewedAt()).isNotNull();
        assertThat(draft.getReviewNote()).isEqualTo("LGTM");
    }

    @Test
    @DisplayName("reject - REJECTED 상태, reviewNote 필수")
    void reject_requiresReviewNote() {
        NotificationTemplateDraft draft = buildDraft();

        draft.reject(9001L, "PayNow UEN 변수 사용 누락 — {{paynowUen}}로 교체 필요");

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.REJECTED);
        assertThat(draft.getReviewNote()).contains("PayNow UEN");
    }

    @Test
    @DisplayName("reject - reviewNote 가 비어있으면 IllegalArgumentException")
    void reject_blankNoteThrows() {
        NotificationTemplateDraft draft = buildDraft();

        assertThatThrownBy(() -> draft.reject(9001L, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewNote");
    }

    @Test
    @DisplayName("withdraw - WITHDRAWN 상태로 전이")
    void withdraw_transitionsToWithdrawn() {
        NotificationTemplateDraft draft = buildDraft();

        draft.withdraw();

        assertThat(draft.getStatus()).isEqualTo(TemplateDraftStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("approve 후 edit 시도 - IllegalStateException (PENDING 아님)")
    void edit_afterApprove_throws() {
        NotificationTemplateDraft draft = buildDraft();
        draft.approve(9001L, "ok");

        assertThatThrownBy(() -> draft.edit(
                "new subject", "new body", "[]", null,
                NotificationCategory.PAYMENT, NotificationSeverity.CRITICAL,
                "APPLICANT", "edit after approve"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("approve 후 reject 시도 - IllegalStateException")
    void reject_afterApprove_throws() {
        NotificationTemplateDraft draft = buildDraft();
        draft.approve(9001L, "ok");

        assertThatThrownBy(() -> draft.reject(9002L, "too late"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING");
    }
}
