package com.bluelight.backend.api.admin.manualemail.dto;

import com.bluelight.backend.domain.manualemail.RecipientType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ADMIN 수동 이메일 발송 요청 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.1.</p>
 *
 * <h3>PR-1 단일 수신자 검증 규칙</h3>
 * <ul>
 *   <li>{@code recipientType=APPLICANT} → {@code recipientUserSeq} 필수, 사용자 role 매칭 검증.</li>
 *   <li>{@code recipientType=LEW} → {@code recipientUserSeq} 필수, 사용자 role 매칭 검증.</li>
 *   <li>{@code recipientType=EXTERNAL} → {@code recipientEmail} 필수, 이메일 형식 검증.</li>
 *   <li>{@code recipientType=MULTI} → 컨트롤러에서 400 {@code MULTI_NOT_SUPPORTED_IN_PR1} 거부 (PR-2 활성화).</li>
 * </ul>
 *
 * <p>{@code subject}/{@code bodyText} 길이 제약은 Bean Validation 으로 강제 (스펙 AC-A6).</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class SendManualEmailRequest {

    @NotNull(message = "Recipient type is required")
    private RecipientType recipientType;

    /** APPLICANT/LEW 수신 시 user_seq. EXTERNAL 일 때는 무시된다. */
    private Long recipientUserSeq;

    /** EXTERNAL 수신 시 이메일 주소. APPLICANT/LEW 일 때는 무시된다. */
    @Email(message = "Recipient email format is invalid")
    @Size(max = 254, message = "Recipient email must be at most 254 characters")
    private String recipientEmail;

    /** 신청 컨텍스트 (옵션). null 가능. */
    private Long relatedApplicationSeq;

    @NotBlank(message = "Subject is required")
    @Size(min = 1, max = 200, message = "Subject must be between 1 and 200 characters")
    private String subject;

    @NotBlank(message = "Body is required")
    @Size(min = 1, max = 50_000, message = "Body must be between 1 and 50,000 characters")
    private String bodyText;

    /** 자유 분류 태그 (옵션). null/빈 값 허용. */
    @Size(max = 50, message = "Category tag must be at most 50 characters")
    private String categoryTag;

    /**
     * 멱등성 검사 우회 플래그 (스펙 AC-A9 / D3=B).
     * 30초 이내 동일 내용이 발송 이력에 발견되면 기본적으로 409 거부 — 클라이언트가 의도적
     * 재발송임을 명시할 때 {@code true} 로 보내 우회.
     */
    private Boolean forceDuplicate;
}
