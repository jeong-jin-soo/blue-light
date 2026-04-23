package com.bluelight.backend.domain.docnumber;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 문서번호 시퀀스 Repository.
 *
 * <p>동시성 제어의 두 축:
 * <ol>
 *   <li>{@link #upsertIfMissing} — row가 없으면 삽입, 있으면 no-op. 미존재 row에 대한
 *       {@code SELECT FOR UPDATE} → gap lock → 동시 INSERT 데드락을 회피.</li>
 *   <li>{@link #findByIdForUpdate} — 존재하는 row에 비관적 쓰기 락. 같은 (type, date)를
 *       요청한 동시 트랜잭션은 커밋/롤백까지 대기.</li>
 * </ol>
 * Spec §5.3 알고리즘 참조.</p>
 */
public interface DocumentNumberSequenceRepository
        extends JpaRepository<DocumentNumberSequence, DocumentNumberSequenceId> {

    /**
     * 비관적 락(SELECT ... FOR UPDATE)으로 시퀀스 row를 조회.
     * 같은 (type, date)를 요청한 동시 트랜잭션은 커밋/롤백까지 대기.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM DocumentNumberSequence s "
         + "WHERE s.docTypeCode = :docTypeCode AND s.issueDate = :issueDate")
    Optional<DocumentNumberSequence> findByIdForUpdate(
            @Param("docTypeCode") String docTypeCode,
            @Param("issueDate") LocalDate issueDate);

    /**
     * 시퀀스 row가 없으면 {@code next_value=1}로 삽입, 있으면 no-op.
     * MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE} 사용 — 미존재 row에 대한
     * {@code SELECT FOR UPDATE} gap lock 문제를 회피하기 위한 멱등 시딩.
     * 이 메서드 단독으로는 시퀀스 값을 보호하지 않으며, 호출자는 이후
     * {@link #findByIdForUpdate}로 반드시 락을 획득해야 한다.
     */
    @Modifying
    @Query(value = "INSERT INTO document_number_sequence "
                 + "(doc_type_code, issue_date, next_value, created_at, updated_at) "
                 + "VALUES (:docTypeCode, :issueDate, 1, NOW(6), NOW(6)) "
                 + "ON DUPLICATE KEY UPDATE doc_type_code = doc_type_code",
           nativeQuery = true)
    int upsertIfMissing(
            @Param("docTypeCode") String docTypeCode,
            @Param("issueDate") LocalDate issueDate);
}
