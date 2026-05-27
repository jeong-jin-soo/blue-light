package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldUploadedDto;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.SldRequest;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.application.SldRequestStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin SLD 도면 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminSldService {

    private final ApplicationRepository applicationRepository;
    private final SldRequestRepository sldRequestRepository;

    /**
     * SLD 요청 조회 (Admin/LEW)
     *
     * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.</p>
     */
    public SldRequestResponse getAdminSldRequest(Long applicationSeq, Long userSeq, String role) {
        ensureLewCanAccess(applicationSeq, userSeq, role);
        validateApplicationExists(applicationSeq);
        return sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .map(SldRequestResponse::from)
                .orElse(null);
    }

    /**
     * SLD 업로드 완료 마킹 (LEW)
     *
     * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.</p>
     */
    @Transactional
    public SldRequestResponse uploadSld(Long applicationSeq, SldUploadedDto dto, Long userSeq, String role) {
        ensureLewCanAccess(applicationSeq, userSeq, role);
        validateApplicationExists(applicationSeq);
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.REQUESTED
                && sldRequest.getStatus() != SldRequestStatus.AI_GENERATING
                && sldRequest.getStatus() != SldRequestStatus.UPLOADED) {
            throw new BusinessException(
                    "SLD can only be uploaded when status is REQUESTED, AI_GENERATING or UPLOADED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
        }

        sldRequest.markUploaded(dto.getFileSeq(), dto.getLewNote());
        log.info("SLD marked as uploaded: applicationSeq={}, fileSeq={}", applicationSeq, dto.getFileSeq());

        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SLD AI 생성 시작 (REQUESTED/UPLOADED → AI_GENERATING)
     *
     * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.</p>
     */
    @Transactional
    public SldRequestResponse startAiGeneration(Long applicationSeq, Long userSeq, String role) {
        ensureLewCanAccess(applicationSeq, userSeq, role);
        validateApplicationExists(applicationSeq);
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.REQUESTED
                && sldRequest.getStatus() != SldRequestStatus.UPLOADED) {
            throw new BusinessException(
                    "AI generation can only start when status is REQUESTED or UPLOADED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
        }

        sldRequest.startAiGeneration();
        log.info("SLD AI generation started: applicationSeq={}", applicationSeq);

        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SLD 확인 (Admin/LEW)
     *
     * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.</p>
     */
    @Transactional
    public SldRequestResponse confirmSld(Long applicationSeq, Long userSeq, String role) {
        ensureLewCanAccess(applicationSeq, userSeq, role);
        validateApplicationExists(applicationSeq);
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.UPLOADED) {
            throw new BusinessException(
                    "SLD can only be confirmed when status is UPLOADED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
        }

        sldRequest.confirm();
        log.info("SLD confirmed: applicationSeq={}", applicationSeq);

        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SLD 확인 해제 (Admin/LEW) — CONFIRMED → UPLOADED
     *
     * <p>★ L-3 (보안 감사 H-2 동일 패턴) — LEW 호출 시 본인 배정 신청서로 한정.</p>
     */
    @Transactional
    public SldRequestResponse unconfirmSld(Long applicationSeq, Long userSeq, String role) {
        ensureLewCanAccess(applicationSeq, userSeq, role);
        validateApplicationExists(applicationSeq);
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.CONFIRMED) {
            throw new BusinessException(
                    "SLD can only be unconfirmed when status is CONFIRMED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
        }

        sldRequest.unconfirm();
        log.info("SLD unconfirmed (reopened): applicationSeq={}", applicationSeq);

        return SldRequestResponse.from(sldRequest);
    }

    private void validateApplicationExists(Long applicationSeq) {
        if (!applicationRepository.existsById(applicationSeq)) {
            throw new BusinessException(
                    "Application not found",
                    HttpStatus.NOT_FOUND,
                    "APPLICATION_NOT_FOUND"
            );
        }
    }

    /**
     * ★ L-3 (보안 감사 H-2 동일 패턴) — LEW cross-tenant 가드.
     *
     * <p>LEW 호출 시 본인 배정 신청서가 아니면 403. ADMIN/SYSTEM_ADMIN 등은 즉시 통과 (DB 미접근).
     * role 은 "ROLE_LEW" 형식 (SimpleGrantedAuthority.getAuthority()). userSeq 는 SecurityContext principal.</p>
     *
     * <p>AdminApplicationService.ensureLewCanAccess 와 동일 패턴이지만, 본 서비스의
     * 메서드들은 Application 엔티티를 직접 보유하지 않으므로 helper 내부에서 findById 로 조회.
     * ADMIN/SA 는 early-return 으로 추가 DB 비용 없음.</p>
     */
    private void ensureLewCanAccess(Long applicationSeq, Long userSeq, String role) {
        if (!"ROLE_LEW".equals(role)) return;
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
        Long assignedLewSeq = application.getAssignedLew() != null
                ? application.getAssignedLew().getUserSeq() : null;
        if (assignedLewSeq == null || !assignedLewSeq.equals(userSeq)) {
            throw new BusinessException("Access denied", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
    }
}
