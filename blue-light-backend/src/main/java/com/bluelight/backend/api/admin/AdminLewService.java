package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminApplicationResponse;
import com.bluelight.backend.api.admin.dto.AssignLewRequest;
import com.bluelight.backend.api.admin.dto.LewSummaryResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

        application.assignLew(lew);
        log.info("LEW assigned: applicationSeq={}, lewSeq={}", applicationSeq, lew.getUserSeq());

        return AdminApplicationResponse.from(application);
    }

    /**
     * 신청에서 LEW 할당 해제
     */
    @Transactional
    public AdminApplicationResponse unassignLew(Long applicationSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        application.unassignLew();
        log.info("LEW unassigned: applicationSeq={}", applicationSeq);
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
