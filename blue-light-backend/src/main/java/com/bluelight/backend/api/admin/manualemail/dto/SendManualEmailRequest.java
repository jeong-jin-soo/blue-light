package com.bluelight.backend.api.admin.manualemail.dto;

import com.bluelight.backend.domain.manualemail.RecipientType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * ADMIN 수동 이메일 발송 요청 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.1.</p>
 *
 * <h3>수신자 검증 규칙</h3>
 * <ul>
 *   <li>{@code recipientType=APPLICANT} → {@code recipientUserSeq} 필수, 사용자 role 매칭 검증.</li>
 *   <li>{@code recipientType=LEW} → {@code recipientUserSeq} 필수, 사용자 role 매칭 검증.</li>
 *   <li>{@code recipientType=EXTERNAL} → {@code recipientEmail} 필수, 이메일 형식 검증.</li>
 *   <li>{@code recipientType=MULTI} (PR-2) → {@code recipientUserSeqs} + {@code recipientEmails}
 *       의 합이 2건 이상 필수. 시스템 사용자 role 매칭 검증 + 외부 이메일 형식 검증.</li>
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

    /** APPLICANT/LEW 수신 시 user_seq. EXTERNAL/MULTI 일 때는 무시된다. */
    private Long recipientUserSeq;

    /** EXTERNAL 수신 시 이메일 주소. APPLICANT/LEW/MULTI 일 때는 무시된다. */
    @Email(message = "Recipient email format is invalid")
    @Size(max = 254, message = "Recipient email must be at most 254 characters")
    private String recipientEmail;

    /**
     * MULTI 발송 시 시스템 사용자 user_seq 목록 (APPLICANT/LEW 혼합 가능).
     *
     * <p>스펙: PR-2 §5.1 / AC-A4. 단일 발송에서는 무시된다. {@link #recipientEmails} 와 합쳐서 2건 이상.
     * 각 user_seq 는 시스템 사용자 lookup 으로 이메일을 추출하며 role 은 APPLICANT 또는 LEW 만 허용.</p>
     */
    @Size(max = 100, message = "Recipient user seqs must be at most 100")
    private List<Long> recipientUserSeqs;

    /**
     * MULTI 발송 시 외부 이메일 목록 (시스템 미등록 EXTERNAL).
     *
     * <p>스펙: PR-2 §5.1. 단일 발송에서는 무시된다. {@link #recipientUserSeqs} 와 합쳐서 2건 이상.
     * 각 항목은 RFC 5322 형식 + 254자 이하. 서비스 레이어에서 정규화(소문자 + 트림) 후 중복 제거.</p>
     */
    @Size(max = 100, message = "Recipient emails must be at most 100")
    private List<@Email(message = "Recipient email format is invalid")
                  @Size(max = 254, message = "Each recipient email must be at most 254 characters") String> recipientEmails;

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

    /**
     * PR-4 (admin-manual-email-spec.md §8.5 / AC-A11 / D4=B): 시스템 사용자 수신자에게 인앱
     * 알림을 동반 생성할지 여부. 기본값(필드 미지정/null) 은 {@code true} — Compose UI 의
     * 기본 체크 ON 과 일치한다.
     *
     * <p>EXTERNAL 수신자는 시스템 계정이 없어 본 플래그와 무관하게 인앱 알림이 발생하지
     * 않는다 (값을 false 로 보내도 동작 변경 없음). MULTI 시 시스템 사용자 부분에만 적용.</p>
     */
    private Boolean alsoCreateInAppNotification;
}
