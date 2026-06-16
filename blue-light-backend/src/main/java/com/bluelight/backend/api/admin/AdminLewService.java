package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminApplicationResponse;
import com.bluelight.backend.api.admin.dto.AssignLewRequest;
import com.bluelight.backend.api.admin.dto.LewSummaryResponse;
import com.bluelight.backend.api.application.LewAssignedEvent;
import com.bluelight.backend.api.application.LewUnassignedEvent;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin LEW 배정 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminLewService {

    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    /** LEW 배정 시 AFTER_COMMIT 알림(인앱+이메일) 트리거 — LewAssignmentNotificationListener 수신. */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 신청에 LEW 할당
     */
    @Transactional
    public AdminApplicationResponse assignLew(Long applicationSeq, AssignLewRequest request) {
        Application application = findApplicationOrThrow(applicationSeq);

        User lew = userRepository.findById(request.getLewUserSeq())
                .orElseThrow(() -> new BusinessException(
                        "LEW user not found", HttpStatus.NOT_FOUND, "LEW_NOT_FOUND"));

        if (lew.getRole() != UserRole.LEW) {
            throw new BusinessException(
                    "User is not a LEW", HttpStatus.BAD_REQUEST, "NOT_LEW_USER");
        }
        if (!lew.isApproved()) {
            throw new BusinessException(
                    "LEW is not approved", HttpStatus.BAD_REQUEST, "LEW_NOT_APPROVED");
        }

        // LEW 등급별 kVA 용량 검증
        if (lew.getLewGrade() == null) {
            throw new BusinessException(
                    "LEW grade is not set. Please update the LEW's grade before assignment.",
                    HttpStatus.BAD_REQUEST, "LEW_GRADE_NOT_SET");
        }
        if (!lew.canHandleKva(application.getSelectedKva())) {
            throw new BusinessException(
                    String.format("LEW grade %s (max %d kVA) cannot handle this application's %d kVA",
                            lew.getLewGrade().name(), lew.getLewGrade().getMaxKva(), application.getSelectedKva()),
                    HttpStatus.BAD_REQUEST, "LEW_GRADE_INSUFFICIENT");
        }

        // 재배정 판정: 기존에 다른 LEW 가 배정돼 있었는지 (덮어쓰기 전에 캡처).
        User previousLew = application.getAssignedLew();
        boolean reassigned = previousLew != null
                && !previousLew.getUserSeq().equals(lew.getUserSeq());

        application.assignLew(lew);
        log.info("LEW assigned: applicationSeq={}, lewSeq={}, reassigned={}",
                applicationSeq, lew.getUserSeq(), reassigned);

        // 새 LEW 에게 배정 알림 (인앱 + 이메일) — AFTER_COMMIT 이벤트로 통일.
        // 자동 배정(ApplicationService) 경로와 동일하게 LewAssignmentNotificationListener 가 처리한다.
        // 기존엔 여기서 이메일만 직접 발송했으나 인앱 알림이 누락돼 있었다.
        User applicant = application.getUser();
        eventPublisher.publishEvent(new LewAssignedEvent(
                applicationSeq,
                lew.getUserSeq(),
                applicant.getFullName(),
                application.getAddress(),
                false,
                reassigned));

        // 재배정이면 떠나는 LEW 에게도 통지 (#4 무알림 해소). 진행물은 보존되어 새 LEW 가 인계.
        if (reassigned) {
            eventPublisher.publishEvent(new LewUnassignedEvent(
                    applicationSeq, previousLew.getUserSeq(), true));
        }

        return AdminApplicationResponse.from(application);
    }

    /**
     * 신청에서 LEW 할당 해제
     */
    @Transactional
    public AdminApplicationResponse unassignLew(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        // 떠나는 LEW 캡처 (해제 전). 진행물은 보존되며 접근권만 회수된다.
        User previousLew = application.getAssignedLew();
        application.unassignLew();
        log.info("LEW unassigned: applicationSeq={}, previousLewSeq={}",
                applicationSeq, previousLew != null ? previousLew.getUserSeq() : null);

        // 떠나는 LEW 에게 통지 (#4 무알림 해소).
        if (previousLew != null) {
            eventPublisher.publishEvent(new LewUnassignedEvent(
                    applicationSeq, previousLew.getUserSeq(), false));
        }
        return AdminApplicationResponse.from(application);
    }

    /**
     * 할당 가능한 LEW 목록 조회 (APPROVED 상태, kVA 필터 선택)
     */
    public List<LewSummaryResponse> getAvailableLews(Integer kva) {
        return userRepository.findByRoleAndApprovedStatus(UserRole.LEW, ApprovalStatus.APPROVED)
                .stream()
                .filter(lew -> kva == null || lew.canHandleKva(kva))
                .map(LewSummaryResponse::from)
                .toList();
    }

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND,
                        "APPLICATION_NOT_FOUND"
                ));
    }
}
