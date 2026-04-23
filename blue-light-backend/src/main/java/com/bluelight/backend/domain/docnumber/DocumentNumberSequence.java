package com.bluelight.backend.domain.docnumber;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일별 문서번호 시퀀스 카운터.
 *
 * <p>복합 PK {@code (docTypeCode, issueDate)}. 행당 하나의 {@code nextValue}를 가진다.
 * {@link DocumentNumberSequenceRepository#findByIdForUpdate} 가 {@code SELECT ... FOR UPDATE}
 * 로 행을 잠그면, 동시에 같은 (type, date) 조합을 요청한 다른 트랜잭션은 대기 → 순차 발번 보장.</p>
 *
 * <p>스펙: {@code doc/Project Analysis/document-number-generator-spec.md §5}.</p>
 */
@Entity
@Table(name = "document_number_sequence")
@IdClass(DocumentNumberSequenceId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentNumberSequence {

    @Id
    @Column(name = "doc_type_code", length = 40, nullable = false, updatable = false)
    private String docTypeCode;

    @Id
    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    /** 다음 발번될 시퀀스 번호. 1부터 시작하여 발번 시마다 +1. */
    @Column(name = "next_value", nullable = false)
    private Integer nextValue;

    @Column(name = "last_issued_at")
    private LocalDateTime lastIssuedAt;

    @Column(name = "last_issued_by")
    private Long lastIssuedBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private DocumentNumberSequence(String docTypeCode, LocalDate issueDate) {
        this.docTypeCode = docTypeCode;
        this.issueDate = issueDate;
        this.nextValue = 1;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 해당 (type, date) 조합의 첫 시퀀스 row를 생성. {@code nextValue = 1}. */
    public static DocumentNumberSequence firstOf(String docTypeCode, LocalDate issueDate) {
        return new DocumentNumberSequence(docTypeCode, issueDate);
    }

    /**
     * 현재 {@code nextValue}를 반환하고 내부 카운터를 +1 증가시킨다.
     * 호출자는 이 메서드의 반환값을 문서번호 숫자부로 사용한다.
     *
     * @param issuedByUserSeq 발번 요청자의 userSeq (비어 있을 수 있음)
     * @return 이번에 발번될 시퀀스 값 (1-indexed)
     */
    public int advance(Long issuedByUserSeq) {
        int issued = this.nextValue;
        this.nextValue = issued + 1;
        this.lastIssuedAt = LocalDateTime.now();
        this.lastIssuedBy = issuedByUserSeq;
        this.updatedAt = this.lastIssuedAt;
        return issued;
    }
}
