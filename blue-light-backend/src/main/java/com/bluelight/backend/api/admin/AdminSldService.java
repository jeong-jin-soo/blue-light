package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.SldUploadedDto;
import com.bluelight.backend.api.application.dto.SldRequestResponse;
import com.bluelight.backend.common.exception.BusinessException;
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
     */
    public SldRequestResponse getAdminSldRequest(Long applicationSeq) {
        validateApplicationExists(applicationSeq);
        return sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .map(SldRequestResponse::from)
                .orElse(null);
    }

    /**
     * SLD 업로드 완료 마킹 (LEW)
     */
    @Transactional
    public SldRequestResponse uploadSld(Long applicationSeq, SldUploadedDto dto) {
        validateApplicationExists(applicationSeq);
        SldRequest sldRequest = sldRequestRepository.findByApplicationApplicationSeq(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "SLD request not found", HttpStatus.NOT_FOUND, "SLD_REQUEST_NOT_FOUND"));

        if (sldRequest.getStatus() != SldRequestStatus.REQUESTED) {
            throw new BusinessException(
                    "SLD can only be uploaded when status is REQUESTED",
                    HttpStatus.BAD_REQUEST, "INVALID_SLD_STATUS");
        }

        sldRequest.markUploaded(dto.getFileSeq(), dto.getLewNote());
        log.info("SLD marked as uploaded: applicationSeq={}, fileSeq={}", applicationSeq, dto.getFileSeq());

        return SldRequestResponse.from(sldRequest);
    }

    /**
     * SLD 확인 (Admin/LEW)
     */
    @Transactional
    public SldRequestResponse confirmSld(Long applicationSeq) {
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

    private void validateApplicationExists(Long applicationSeq) {
        if (!applicationRepository.existsById(applicationSeq)) {
            throw new BusinessException(
                    "Application not found",
                    HttpStatus.NOT_FOUND,
                    "APPLICATION_NOT_FOUND"
            );
        }
    }
}
