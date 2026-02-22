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
    private String firstName;
    private String lastName;
    private String role;
    private Boolean approved;
    private Boolean emailVerified;

    public static TokenResponse of(String accessToken, Long expiresIn, Long userSeq,
                                   String email, String firstName, String lastName, String role,
                                   boolean approved, boolean emailVerified) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(expiresIn)
                .userSeq(userSeq)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .role(role)
                .approved(approved)
                .emailVerified(emailVerified)
                .build();
    }
}
