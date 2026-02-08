package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Admin user list response DTO
 */
@Getter
@Builder
public class AdminUserResponse {

    private Long userSeq;
    private String email;
    private String name;
    private String phone;
    private UserRole role;
    private LocalDateTime createdAt;

    public static AdminUserResponse from(User user) {
        return AdminUserResponse.builder()
                .userSeq(user.getUserSeq())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
