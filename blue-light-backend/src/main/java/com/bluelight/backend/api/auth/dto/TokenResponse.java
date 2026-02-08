package com.bluelight.backend.api.auth.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 토큰 응답 DTO
 */
@Getter
@Builder
public class TokenResponse {

    private String accessToken;
    private String tokenType;
    private Long expiresIn;
    private Long userSeq;
    private String email;
    private String name;
    private String role;
    private Boolean approved;

    public static TokenResponse of(String accessToken, Long expiresIn, Long userSeq,
                                   String email, String name, String role, boolean approved) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .userSeq(userSeq)
                .email(email)
                .name(name)
                .role(role)
                .approved(approved)
                .build();
    }
}
