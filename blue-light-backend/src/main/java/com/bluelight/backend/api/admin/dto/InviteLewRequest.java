package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ADMIN LEW 초대 요청 DTO (PR-1).
 * <p>
 * 면허번호·등급·PayNow 는 초대 시점에 받지 않는다 — LEW 가 셋업 화면에서 직접 입력(D-2).
 * 따라서 ADMIN 은 이메일·이름만 입력한다.
 */
@Getter
@NoArgsConstructor
public class InviteLewRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 100)
    private String email;

    @NotBlank(message = "First name is required")
    @Size(max = 50)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50)
    private String lastName;
}
