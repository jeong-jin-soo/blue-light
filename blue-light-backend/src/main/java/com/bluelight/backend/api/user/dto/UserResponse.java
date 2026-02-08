package com.bluelight.backend.api.user.dto;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * User profile response DTO
 */
@Getter
@Builder
public class UserResponse {

    private Long userSeq;
    private String email;
    private String name;
    private String phone;
    private UserRole role;
    private boolean approved;
    private LocalDateTime createdAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .userSeq(user.getUserSeq())
                .email(user.getEmail())
                .name(user.getName())
                .phone(user.getPhone())
                .role(user.getRole())
                .approved(user.isApproved())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
