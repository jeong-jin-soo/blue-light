package com.bluelight.backend.api.user;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.user.dto.ChangePasswordRequest;
import com.bluelight.backend.api.user.dto.UpdateProfileRequest;
import com.bluelight.backend.api.user.dto.UserResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.EnumParser;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.audit.AuditLogRepository;
import com.bluelight.backend.domain.chat.ChatMessage;
import com.bluelight.backend.domain.chat.ChatMessageRepository;
import com.bluelight.backend.domain.user.LewGrade;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * User profile service
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;
    private final ApplicationRepository applicationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    /**
     * Get current user profile
     */
    public UserResponse getProfile(Long userSeq) {
        User user = findUserOrThrow(userSeq);
        return UserResponse.from(user);
    }

    /**
     * Update profile (name, phone, company info)
     */
    @Transactional
    public UserResponse updateProfile(Long userSeq, UpdateProfileRequest request) {
        User user = findUserOrThrow(userSeq);

        // LEW 등급 파싱
        LewGrade lewGrade = EnumParser.parseNullable(LewGrade.class, request.getLewGrade(), "INVALID_LEW_GRADE");

        user.updateProfile(
                request.getName(),
                request.getPhone(),
                request.getLewLicenceNo(),
                lewGrade,
                request.getCompanyName(),
                request.getUen(),
                request.getDesignation(),
                request.getCorrespondenceAddress(),
                request.getCorrespondencePostalCode()
        );
        log.info("Profile updated: userSeq={}", userSeq);
        return UserResponse.from(user);
    }

    /**
     * Change password
     */
    @Transactional
    public void changePassword(Long userSeq, ChangePasswordRequest request) {
        User user = findUserOrThrow(userSeq);

        // Verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException("Current password is incorrect", HttpStatus.BAD_REQUEST, "INVALID_PASSWORD");
        }

        // Encode and update
        String encodedNewPassword = passwordEncoder.encode(request.getNewPassword());
        user.changePassword(encodedNewPassword);
        log.info("Password changed: userSeq={}", userSeq);
    }

    /**
     * Upload or replace profile signature
     */
    @Transactional
    public UserResponse uploadSignature(Long userSeq, MultipartFile signatureImage) {
        User user = findUserOrThrow(userSeq);

        // 기존 서명 파일 삭제
        if (user.getSignatureUrl() != null) {
            fileStorageService.delete(user.getSignatureUrl());
        }

        // 새 서명 저장
        String relativePath = fileStorageService.store(signatureImage, "users/" + userSeq);
        user.updateSignatureUrl(relativePath);
        log.info("Signature uploaded: userSeq={}, path={}", userSeq, relativePath);
        return UserResponse.from(user);
    }

    /**
     * Delete profile signature
     */
    @Transactional
    public void deleteSignature(Long userSeq) {
        User user = findUserOrThrow(userSeq);

        if (user.getSignatureUrl() == null) {
            throw new BusinessException("No signature to delete", HttpStatus.BAD_REQUEST, "NO_SIGNATURE");
        }

        fileStorageService.delete(user.getSignatureUrl());
        user.removeSignatureUrl();
        log.info("Signature deleted: userSeq={}", userSeq);
    }

    /**
     * Get signature image as Resource
     */
    public Resource getSignatureResource(Long userSeq) {
        User user = findUserOrThrow(userSeq);

        if (user.getSignatureUrl() == null) {
            throw new BusinessException("No signature found", HttpStatus.NOT_FOUND, "NO_SIGNATURE");
        }

        return fileStorageService.loadAsResource(user.getSignatureUrl());
    }

    /**
     * PDPA: 동의 철회 (Right to Withdrawal)
     * - pdpaConsentAt을 null로 설정
     * - 계정은 유지하되, 동의 기반 서비스(챗봇 등) 제한
     */
    @Transactional
    public void withdrawPdpaConsent(Long userSeq) {
        User user = findUserOrThrow(userSeq);

        if (!user.hasPdpaConsent()) {
            throw new BusinessException("PDPA consent has already been withdrawn", HttpStatus.BAD_REQUEST, "CONSENT_ALREADY_WITHDRAWN");
        }

        user.withdrawPdpaConsent();

        // 감사 로그 기록
        auditLogService.logAsync(
                userSeq, AuditAction.PDPA_CONSENT_WITHDRAWN, AuditCategory.DATA_PROTECTION,
                "User", String.valueOf(userSeq), "PDPA consent withdrawn by user",
                null, null,
                null, null, "POST", "/api/users/me/withdraw-consent", 200
        );

        log.info("PDPA consent withdrawn: userSeq={}", userSeq);
    }

    /**
     * PDPA: 사용자 데이터 내보내기 (Right to Access / Data Portability)
     * - 프로필, 신청 내역, 채팅 기록을 JSON으로 반환
     */
    public Map<String, Object> exportUserData(Long userSeq) {
        User user = findUserOrThrow(userSeq);

        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 프로필 정보
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", user.getEmail());
        profile.put("name", user.getName());
        profile.put("phone", user.getPhone());
        profile.put("role", user.getRole().name());
        profile.put("lewLicenceNo", user.getLewLicenceNo());
        profile.put("lewGrade", user.getLewGrade() != null ? user.getLewGrade().name() : null);
        profile.put("companyName", user.getCompanyName());
        profile.put("uen", user.getUen());
        profile.put("designation", user.getDesignation());
        profile.put("correspondenceAddress", user.getCorrespondenceAddress());
        profile.put("correspondencePostalCode", user.getCorrespondencePostalCode());
        profile.put("emailVerified", user.isEmailVerified());
        profile.put("pdpaConsentAt", user.getPdpaConsentAt());
        profile.put("createdAt", user.getCreatedAt());
        data.put("profile", profile);

        // 2. 신청 내역
        List<Application> applications = applicationRepository.findByUserUserSeqOrderByCreatedAtDesc(userSeq);
        List<Map<String, Object>> appList = applications.stream().map(app -> {
            Map<String, Object> appMap = new LinkedHashMap<>();
            appMap.put("applicationSeq", app.getApplicationSeq());
            appMap.put("address", app.getAddress());
            appMap.put("postalCode", app.getPostalCode());
            appMap.put("buildingType", app.getBuildingType());
            appMap.put("selectedKva", app.getSelectedKva());
            appMap.put("quoteAmount", app.getQuoteAmount());
            appMap.put("status", app.getStatus().name());
            appMap.put("applicationType", app.getApplicationType().name());
            appMap.put("licenseNumber", app.getLicenseNumber());
            appMap.put("licenseExpiryDate", app.getLicenseExpiryDate());
            appMap.put("createdAt", app.getCreatedAt());
            appMap.put("updatedAt", app.getUpdatedAt());
            return appMap;
        }).collect(Collectors.toList());
        data.put("applications", appList);

        // 3. 채팅 기록
        List<ChatMessage> chatMessages = chatMessageRepository.findByUserSeqOrderByCreatedAtDesc(userSeq);
        List<Map<String, Object>> chatList = chatMessages.stream().map(msg -> {
            Map<String, Object> msgMap = new LinkedHashMap<>();
            msgMap.put("sessionId", msg.getSessionId());
            msgMap.put("role", msg.getRole());
            msgMap.put("content", msg.getContent());
            msgMap.put("createdAt", msg.getCreatedAt());
            return msgMap;
        }).collect(Collectors.toList());
        data.put("chatMessages", chatList);

        data.put("exportedAt", java.time.LocalDateTime.now());

        // 감사 로그 기록
        auditLogService.logAsync(
                userSeq, AuditAction.DATA_EXPORTED, AuditCategory.DATA_PROTECTION,
                "USER", String.valueOf(userSeq), "User data export requested",
                null, null,
                null, null, "GET", "/api/users/me/data-export", 200
        );

        log.info("Data exported: userSeq={}", userSeq);
        return data;
    }

    /**
     * PDPA: 계정 삭제 (Right to Erasure)
     * - 개인정보 익명화 + soft delete
     * - 법적 보존 의무가 있는 신청 기록은 유지 (5년)
     * - 서명 이미지 삭제
     * - 채팅 기록 삭제
     * - 감사 로그 내 개인정보 익명화
     */
    @Transactional
    public void deleteAccount(Long userSeq) {
        User user = findUserOrThrow(userSeq);
        String originalEmail = user.getEmail();

        // 감사 로그 기록 (삭제 전에 기록)
        auditLogService.log(
                userSeq, originalEmail, user.getRole().name(),
                AuditAction.ACCOUNT_DELETED, AuditCategory.DATA_PROTECTION,
                "USER", String.valueOf(userSeq), "Account deletion requested by user",
                null, null,
                null, null, "DELETE", "/api/users/me", 204
        );

        // 1. 서명 파일 삭제
        if (user.getSignatureUrl() != null) {
            try {
                fileStorageService.delete(user.getSignatureUrl());
            } catch (Exception e) {
                log.warn("서명 파일 삭제 실패: {}", user.getSignatureUrl(), e);
            }
        }

        // 2. 채팅 기록 삭제
        List<ChatMessage> chatMessages = chatMessageRepository.findByUserSeqOrderByCreatedAtDesc(userSeq);
        if (!chatMessages.isEmpty()) {
            chatMessageRepository.deleteAll(chatMessages);
            log.info("채팅 기록 삭제: userSeq={}, count={}", userSeq, chatMessages.size());
        }

        // 3. 감사 로그 내 개인정보 익명화 (이메일, IP, User-Agent, before/after JSON)
        int anonymizedCount = auditLogRepository.anonymizeByUserSeq(userSeq, originalEmail);
        log.info("감사 로그 익명화: userSeq={}, count={}", userSeq, anonymizedCount);

        // 4. 개인정보 익명화
        user.anonymize();

        // 5. soft delete (deleted_at 설정)
        userRepository.delete(user);

        log.info("Account deleted (anonymized): userSeq={}", userSeq);
    }

    private User findUserOrThrow(Long userSeq) {
        return userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
