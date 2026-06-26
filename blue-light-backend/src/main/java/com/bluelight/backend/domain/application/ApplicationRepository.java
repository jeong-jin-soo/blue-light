package com.bluelight.backend.domain.application;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Application Entity Repository
 */
@Repository
public interface ApplicationRepository extends JpaRepository<Application, Long>, JpaSpecificationExecutor<Application> {

    /**
     * 특정 사용자의 신청 목록 조회
     */
    List<Application> findByUserUserSeq(Long userSeq);

    /**
     * 특정 사용자의 신청 목록 조회 (최신순)
     */
    List<Application> findByUserUserSeqOrderByCreatedAtDesc(Long userSeq);

    /**
     * 특정 상태의 신청 목록 조회
     */
    List<Application> findByStatus(ApplicationStatus status);

    /**
     * 특정 사용자의 특정 상태 신청 목록 조회
     */
    List<Application> findByUserUserSeqAndStatus(Long userSeq, ApplicationStatus status);

    /**
     * 특정 사용자의 특정 상태 신청 목록 (최신순) — 갱신 시 완료 신청 조회용
     */
    List<Application> findByUserUserSeqAndStatusOrderByCreatedAtDesc(Long userSeq, ApplicationStatus status);

    /**
     * 전체 신청 목록 페이지네이션 (Admin)
     */
    Page<Application> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 상태별 전체 신청 목록 페이지네이션 (Admin)
     */
    Page<Application> findByStatusOrderByCreatedAtDesc(ApplicationStatus status, Pageable pageable);

    /**
     * 검색: 주소, 이름, 이메일, ID로 검색 (Admin)
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 상태 + 검색 복합 (Admin)
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "a.status = :status AND " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeywordAndStatus(@Param("keyword") String keyword, @Param("status") ApplicationStatus status, Pageable pageable);

    /**
     * 상태별 건수 (Admin dashboard)
     */
    long countByStatus(ApplicationStatus status);

    /**
     * 미할당 신청 건수
     */
    long countByAssignedLewIsNull();

    /**
     * 특정 LEW에게 할당된 신청 건수
     */
    long countByAssignedLewUserSeq(Long lewSeq);

    /**
     * 특정 LEW에게 할당된 신청 목록 (최신순, 페이지네이션)
     */
    Page<Application> findByAssignedLewUserSeqOrderByCreatedAtDesc(Long lewSeq, Pageable pageable);

    /**
     * 특정 LEW에게 할당된 + 특정 상태의 신청 목록
     */
    Page<Application> findByAssignedLewUserSeqAndStatusOrderByCreatedAtDesc(
            Long lewSeq, ApplicationStatus status, Pageable pageable);

    /**
     * LEW 전용: 할당된 신청서 중 키워드 검색
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "a.assignedLew.userSeq = :lewSeq AND " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeywordAndAssignedLew(
            @Param("keyword") String keyword, @Param("lewSeq") Long lewSeq, Pageable pageable);

    /**
     * LEW 전용: 할당된 신청서 중 키워드 + 상태 검색
     */
    @Query("SELECT a FROM Application a JOIN a.user u WHERE " +
           "a.assignedLew.userSeq = :lewSeq AND " +
           "a.status = :status AND " +
           "(LOWER(a.address) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "CAST(a.applicationSeq AS string) LIKE CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.createdAt DESC")
    Page<Application> searchByKeywordAndStatusAndAssignedLew(
            @Param("keyword") String keyword, @Param("status") ApplicationStatus status,
            @Param("lewSeq") Long lewSeq, Pageable pageable);

    /**
     * LEW 전용: 할당된 신청서 중 특정 상태 건수
     */
    long countByAssignedLewUserSeqAndStatus(Long lewSeq, ApplicationStatus status);

    /**
     * 비관적 락으로 application row 를 조회 (B-3 rate limit race 방지).
     * {@link DocumentRequest} 배치 생성 진입 시 호출하여 active request count 재검사를 직렬화한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Application a WHERE a.applicationSeq = :id")
    Optional<Application> findByIdForUpdate(@Param("id") Long id);

    /**
     * 라이선스 만료 처리 대상: COMPLETED + licenseStatus=ACTIVE + 만료일 경과.
     * ACTIVE 만 대상이라 이미 EXPIRED 처리된 건은 재처리되지 않는다.
     */
    List<Application> findByStatusAndLicenseStatusAndLicenseExpiryDateBefore(
            ApplicationStatus status, LicenseStatus licenseStatus, LocalDate date);

    /**
     * 만료 알림 대상: COMPLETED + 만료일 임박 + 미알림
     */
    List<Application> findByStatusAndLicenseExpiryDateLessThanEqualAndExpiryNotifiedAtIsNull(
            ApplicationStatus status, LocalDate date);

    /**
     * 라이선스 상태별 집계 (대시보드) — 예: COMPLETED + licenseStatus=EXPIRED = 만료 라이선스 수.
     */
    long countByStatusAndLicenseStatus(ApplicationStatus status, LicenseStatus licenseStatus);

    /** LEW 전용 — 배정 LEW 의 라이선스 상태별 집계. */
    long countByAssignedLewUserSeqAndStatusAndLicenseStatus(
            Long lewSeq, ApplicationStatus status, LicenseStatus licenseStatus);

    /**
     * EMA 제출 리마인더 대상 (PR-E5, ema-submission-tracking-spec.md §10).
     *
     * <p>조건:
     * <ul>
     *   <li>{@code status = IN_PROGRESS} — EMA 서브-상태 기계는 IN_PROGRESS 에서만 동작(NG3).</li>
     *   <li>{@code ema_submission_status ∈ {SUBMITTED, RESUBMITTED}} — 제출됐으나 결정 대기 중.</li>
     *   <li>{@code ema_submitted_at < :cutoff} — 제출 후 N일(=ema.reminder.days) 경과.</li>
     *   <li>{@code ema_reminder_notified_at IS NULL OR < :startOfToday} — 오늘 아직 미발송(1일 1회 멱등).</li>
     * </ul>
     * cutoff/startOfToday 는 스케줄러가 {@code EmaSubmissionSettings.reminder.days} 로 계산해 전달한다
     * (설정 우선 — 임계값 하드코딩 금지).
     */
    @Query("SELECT a FROM Application a WHERE a.status = :inProgress "
            + "AND a.emaSubmissionStatus IN (:submitted, :resubmitted) "
            + "AND a.emaSubmittedAt < :cutoff "
            + "AND (a.emaReminderNotifiedAt IS NULL OR a.emaReminderNotifiedAt < :startOfToday)")
    List<Application> findEmaReminderTargets(
            @Param("inProgress") ApplicationStatus inProgress,
            @Param("submitted") EmaSubmissionStatus submitted,
            @Param("resubmitted") EmaSubmissionStatus resubmitted,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("startOfToday") LocalDateTime startOfToday);

    /**
     * SLD 미제출 리마인더 후보 (SldReminderScheduler).
     * <p>조건:
     * <ul>
     *   <li>{@code status = COMPLETED} + {@code license_status = ACTIVE} — 발급 완료·유효 라이선스.</li>
     *   <li>{@code assigned_lew IS NOT NULL} — 수신자(담당 LEW)가 있어야 함.</li>
     *   <li>{@code :windowEnd <= license_issued_at <= :windowStart} — 발급 후 2~3개월 구간.</li>
     *   <li>{@code sld_reminder_notified_at IS NULL OR < :dedupeBefore} — 최근(주1회) 미발송.</li>
     * </ul>
     * SLD 존재 여부(DRAWING_SLD 파일 유무)는 스케줄러가 루프에서 별도 필터한다.
     * windowStart=now-2개월, windowEnd=now-3개월, dedupeBefore=now-6일 (스케줄러가 계산).
     */
    @Query("SELECT a FROM Application a WHERE a.status = :completed "
            + "AND a.licenseStatus = :active "
            + "AND a.assignedLew IS NOT NULL "
            + "AND a.licenseIssuedAt IS NOT NULL "
            + "AND a.licenseIssuedAt <= :windowStart "
            + "AND a.licenseIssuedAt >= :windowEnd "
            + "AND (a.sldReminderNotifiedAt IS NULL OR a.sldReminderNotifiedAt < :dedupeBefore)")
    List<Application> findSldReminderCandidates(
            @Param("completed") ApplicationStatus completed,
            @Param("active") LicenseStatus active,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd,
            @Param("dedupeBefore") LocalDateTime dedupeBefore);
}
