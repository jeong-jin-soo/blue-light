package com.bluelight.backend.domain.concierge;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — assignLewWithTransition 도메인 단위 테스트.
 *
 * <p>스펙: §5.2 전이표 + §10 AC-L4 (재할당), §14 PR-3 D 항목.</p>
 */
@DisplayName("ConciergeRequest.assignLewWithTransition - PR-3")
class ConciergeRequestAssignLewTransitionTest {

    private User makeApplicant() {
        return User.builder()
            .email("a@y.com").password("h")
            .firstName("A").lastName("B")
            .role(UserRole.APPLICANT)
            .build();
    }

    private User makeManager() {
        return User.builder()
            .email("m@y.com").password("h")
            .firstName("M").lastName("N")
            .role(UserRole.CONCIERGE_MANAGER)
            .build();
    }

    private ConciergeRequest createRequest() {
        LocalDateTime now = LocalDateTime.now();
        return ConciergeRequest.builder()
            .publicCode("C-2026-0001")
            .submitterName("S").submitterEmail("s@y.com").submitterPhone("+6512345678")
            .applicantUser(makeApplicant())
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
    }

    @Test
    @DisplayName("CONTACTING → LEW_ASSIGNED 전이 (D 항목)")
    void contactingToLewAssigned_succeeds() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.CONTACTING);

        Long previous = cr.assignLewWithTransition(50L, LocalDateTime.now());

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
        assertThat(cr.getAssignedLewSeq()).isEqualTo(50L);
        assertThat(previous).isNull();
    }

    @Test
    @DisplayName("QUOTE_SENT → LEW_ASSIGNED 전이")
    void quoteSentToLewAssigned_succeeds() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        cr.recordQuote(BigDecimal.valueOf(350), null);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.QUOTE_SENT);

        cr.assignLewWithTransition(50L, LocalDateTime.now());
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
    }

    @Test
    @DisplayName("APPLICATION_CREATED → LEW_ASSIGNED 전이 (이미 신청서 만든 후 LEW 할당)")
    void applicationCreatedToLewAssigned_succeeds() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        cr.linkApplication(42L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);

        cr.assignLewWithTransition(50L, LocalDateTime.now());
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
    }

    @Test
    @DisplayName("LEW_ASSIGNED → LEW_ASSIGNED 재할당 (멱등 전이) + previousLewSeq 반환")
    void reassignment_returnsPreviousLewSeq() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        cr.assignLewWithTransition(50L, LocalDateTime.now());
        assertThat(cr.getAssignedLewSeq()).isEqualTo(50L);

        Long previous = cr.assignLewWithTransition(60L, LocalDateTime.now());
        assertThat(cr.getAssignedLewSeq()).isEqualTo(60L);
        assertThat(previous).isEqualTo(50L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
    }

    @Test
    @DisplayName("SUBMITTED → LEW_ASSIGNED 진입 불가 (상태 가드)")
    void submittedToLewAssigned_throws() {
        ConciergeRequest cr = createRequest();
        // SUBMITTED — 매니저 미배정

        assertThatThrownBy(() -> cr.assignLewWithTransition(50L, LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ASSIGNED → LEW_ASSIGNED 진입 불가 (CONTACTING 거쳐야 함)")
    void assignedToLewAssigned_throws() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());

        assertThatThrownBy(() -> cr.assignLewWithTransition(50L, LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCELLED → LEW_ASSIGNED 진입 불가")
    void cancelledToLewAssigned_throws() {
        ConciergeRequest cr = createRequest();
        cr.cancel("test");

        assertThatThrownBy(() -> cr.assignLewWithTransition(50L, LocalDateTime.now()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("LEW_ASSIGNED → APPLICATION_CREATED 전이 (LEW 가 신청서 작성)")
    void lewAssignedToApplicationCreated_succeeds() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        cr.assignLewWithTransition(50L, LocalDateTime.now());
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);

        cr.linkApplication(42L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);
    }

    @Test
    @DisplayName("LEW_ASSIGNED → CANCELLED 전이 가능")
    void lewAssignedToCancelled_succeeds() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();
        cr.assignLewWithTransition(50L, LocalDateTime.now());

        cr.cancel("by manager");
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("assignLewWithTransition(null) → IllegalArgumentException")
    void nullLewSeq_throws() {
        ConciergeRequest cr = createRequest();
        cr.assignManager(makeManager());
        cr.markContacted();

        assertThatThrownBy(() -> cr.assignLewWithTransition(null, LocalDateTime.now()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lewUserSeq");
    }
}
