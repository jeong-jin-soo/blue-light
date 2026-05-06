package com.bluelight.backend.domain.concierge;

import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * 컨시어지 담당자 연락 기록 (★ Kaki Concierge v1.5, PRD §3.3)
 * <p>
 * Manager가 신청자와 연락한 이력(전화/이메일/WhatsApp 등)을 기록한다.
 * 첫 노트 추가 시 {@link ConciergeRequest#markContacted()}가 호출되어
 * {@code firstContactAt}이 세팅되고 24h SLA 카운트가 종료된다.
 */
@Entity
@Table(name = "concierge_notes", indexes = {
    @Index(name = "idx_concierge_note_request", columnList = "concierge_request_seq, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE concierge_notes SET deleted_at = NOW(6) WHERE concierge_note_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class ConciergeNote extends BaseEntity {

    private static final int MAX_CONTENT_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "concierge_note_seq")
    private Long conciergeNoteSeq;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "concierge_request_seq", nullable = false)
    private ConciergeRequest conciergeRequest;

    /**
     * 노트 작성자 (주로 CONCIERGE_MANAGER, 관리자도 가능)
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_user_seq", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NoteChannel channel;

    /**
     * 노트 본문 (최대 2000자, XSS-safe 렌더링은 렌더 레이어에서 처리)
     */
    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Builder
    public ConciergeNote(ConciergeRequest conciergeRequest, User author, NoteChannel channel, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Note content must not be blank");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("Note content exceeds " + MAX_CONTENT_LENGTH + " chars");
        }
        this.conciergeRequest = conciergeRequest;
        this.author = author;
        this.channel = channel;
        this.content = content;
    }
}
