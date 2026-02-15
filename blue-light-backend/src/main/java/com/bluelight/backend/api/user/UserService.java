package com.bluelight.backend.api.user;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.api.user.dto.ChangePasswordRequest;
import com.bluelight.backend.api.user.dto.UpdateProfileRequest;
import com.bluelight.backend.api.user.dto.UserResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.EnumParser;
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

    private User findUserOrThrow(Long userSeq) {
        return userRepository.findById(userSeq)
                .orElseThrow(() -> new BusinessException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
