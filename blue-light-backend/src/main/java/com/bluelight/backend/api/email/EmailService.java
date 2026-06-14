package com.bluelight.backend.api.email;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 이메일 발송 서비스 인터페이스
 * - 구현체 교체 가능 (SMTP, AWS SES, SendGrid 등)
 */
public interface EmailService {

    /**
     * 비밀번호 재설정 이메일 발송
     *
     * @param to        수신자 이메일
     * @param userName  수신자 이름
     * @param resetLink 비밀번호 재설정 링크
     */
    void sendPasswordResetEmail(String to, String userName, String resetLink);

    /**
     * 이메일 인증 이메일 발송
     *
     * @param to               수신자 이메일
     * @param userName         수신자 이름
     * @param verificationLink 이메일 인증 링크
     */
    void sendEmailVerificationEmail(String to, String userName, String verificationLink);

    /**
     * 면허 만료 알림 이메일 발송
     *
     * @param to            수신자 이메일
     * @param userName      수신자 이름
     * @param licenseNumber 면허 번호
     * @param address       설치 주소
     * @param expiryDate    만료일
     * @param daysRemaining 만료까지 남은 일수
     */
    void sendLicenseExpiryWarningEmail(String to, String userName,
                                        String licenseNumber, String address,
                                        LocalDate expiryDate, int daysRemaining);

    // ── 신청서 상태 변경 알림 ──────────────────────

    /**
     * 보완 요청 알림 이메일
     *
     * @param to        신청자 이메일
     * @param userName  신청자 이름
     * @param appSeq    신청서 번호
     * @param address   설치 주소
     * @param comment   보완 요청 코멘트
     */
    void sendRevisionRequestEmail(String to, String userName, Long appSeq, String address, String comment);

    /**
     * 결제 요청 알림 이메일 (LEW 승인 후 결제 대기)
     *
     * @param to        신청자 이메일
     * @param userName  신청자 이름
     * @param appSeq    신청서 번호
     * @param address   설치 주소
     * @param amount    결제 금액
     */
    void sendPaymentRequestEmail(String to, String userName, Long appSeq, String address, BigDecimal amount);

    /**
     * 결제 확인 알림 이메일
     *
     * @param to        신청자 이메일
     * @param userName  신청자 이름
     * @param appSeq    신청서 번호
     * @param address   설치 주소
     * @param amount    결제 금액
     */
    void sendPaymentConfirmEmail(String to, String userName, Long appSeq, String address, BigDecimal amount);

    /**
     * 면허 발급 완료 알림 이메일
     *
     * @param to          신청자 이메일
     * @param userName    신청자 이름
     * @param appSeq      신청서 번호
     * @param address     설치 주소
     * @param licenseNo   발급된 면허 번호
     * @param expiryDate  면허 만료일
     */
    void sendLicenseIssuedEmail(String to, String userName, Long appSeq,
                                 String address, String licenseNo, LocalDate expiryDate);

    /**
     * LEW 할당 알림 이메일 (LEW에게 발송)
     *
     * @param to       LEW 이메일
     * @param lewName  LEW 이름
     * @param appSeq   신청서 번호
     * @param address  설치 주소
     * @param applicantName 신청자 이름
     */
    void sendLewAssignedEmail(String to, String lewName, Long appSeq, String address, String applicantName);

    /**
     * 결제 확인 알림 이메일 (LEW에게 발송)
     *
     * @param to       LEW 이메일
     * @param lewName  LEW 이름
     * @param appSeq   신청서 번호
     * @param address  설치 주소
     * @param amount   결제 금액
     */
    void sendPaymentConfirmedToLewEmail(String to, String lewName, Long appSeq, String address, BigDecimal amount);

    /**
     * PR-2: 결제 후 ADMIN 이 kVA 를 변경한 직후, 배정된 LEW 에게 발송하는 알림 이메일.
     *
     * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1 AC-A1,
     * §8 PR-2. 톤: notification-copy-templates.en.md 의 LEW 섹션 — 격식체 + 단일 CTA + 반피싱 푸터.</p>
     *
     * <p>Subject 는 {@code applicationSeq} 만 노출 (PDPA 최소화 — kVA 수치/금액은 본문에만).</p>
     *
     * @param to                    LEW 이메일
     * @param lewName               LEW 이름 (인사말)
     * @param appSeq                신청서 번호 (CTA URL/제목)
     * @param previousKva           변경 전 kVA
     * @param newKva                변경 후 kVA
     * @param previousQuoteAmount   변경 전 견적가 (nullable — 알 수 없으면 표시 생략)
     * @param newQuoteAmount        변경 후 견적가 (nullable)
     * @param amountDifference      차액 (signed, nullable)
     * @param reason                ADMIN 이 입력한 사유 (HTML escape 후 본문 표시)
     */
    void sendKvaAdjustedToLewEmail(String to, String lewName, Long appSeq,
                                    Integer previousKva, Integer newKva,
                                    BigDecimal previousQuoteAmount, BigDecimal newQuoteAmount,
                                    BigDecimal amountDifference,
                                    String reason);

    /**
     * PR-3: LEW 가 결제 후 kVA 변경을 요청한 직후, ADMIN/SYSTEM_ADMIN 사용자에게 발송하는 알림 이메일.
     *
     * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.2 / PR-3.
     * 톤: notification-copy-templates.en.md ADMIN 섹션 — 격식체 + 단일 CTA + 반피싱 푸터.</p>
     *
     * <p>Subject 는 {@code applicationSeq} 만 노출 (PDPA 최소화 — kVA 수치/금액 미노출).</p>
     *
     * @param to              수신자 ADMIN 이메일
     * @param adminName       수신자 ADMIN 이름 (인사말, escape 후 사용)
     * @param lewName         요청자 LEW 이름 (본문 표기)
     * @param appSeq          신청서 번호 (Subject + CTA URL)
     * @param proposedKva     LEW 가 제안한 kVA
     * @param currentKva      현재 application.selectedKva (참조 표시용)
     * @param reason          LEW 가 입력한 사유 (HTML escape 후 본문 표시)
     */
    void sendKvaAdjustmentRequestedToAdminEmail(String to, String adminName, String lewName, Long appSeq,
                                                 Integer proposedKva, Integer currentKva, String reason);

    /**
     * PR-4: ADMIN 이 settlement 를 마킹한 직후, 배정된 LEW 에게 발송하는 알림 이메일.
     *
     * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.3 / PR-4. 톤은
     * notification-copy-templates.en.md 의 LEW 섹션 — 격식체 + 단일 CTA + 반피싱 푸터.</p>
     *
     * <p>Subject 는 {@code applicationSeq} 만 노출 (PDPA 최소화 — 금액·영수증 번호 미노출).</p>
     *
     * @param to                       LEW 이메일
     * @param lewName                  LEW 이름 (인사말)
     * @param appSeq                   신청서 번호 (CTA URL/제목)
     * @param paymentAdjustment        정산 상태 라벨 ("PAID_DIFFERENCE" / "REFUNDED" / "WAIVED")
     * @param settledAmount            실제 송금/환불 금액 (nullable — 알 수 없으면 표시 생략)
     * @param receiptReferenceNumber   외부 채널 참조번호 (nullable)
     */
    void sendKvaSettlementMarkedToLewEmail(String to, String lewName, Long appSeq,
                                            String paymentAdjustment,
                                            java.math.BigDecimal settledAmount,
                                            String receiptReferenceNumber);

    // ── Phase 3 PR#4 · LEW Document Request Workflow ──────────────────────

    /**
     * 서류 요청 생성 알림 (신청자 수신)
     *
     * @param to              신청자 이메일
     * @param userName        신청자 이름
     * @param appSeq          신청서 번호
     * @param requestedCount  요청 건수
     * @param documentLabels  요청된 문서 라벨 목록 (catalog label 또는 customLabel, 영문)
     */
    void sendDocumentRequestCreatedEmail(String to, String userName, Long appSeq,
                                          int requestedCount, java.util.List<String> documentLabels);

    /**
     * 서류 업로드 알림 (할당 LEW 수신)
     *
     * @param to             LEW 이메일
     * @param lewName        LEW 이름
     * @param appSeq         신청서 번호
     * @param documentLabel  업로드된 문서 라벨
     */
    void sendDocumentRequestFulfilledEmail(String to, String lewName, Long appSeq,
                                            String documentLabel);

    /**
     * 서류 승인 알림 (신청자 수신)
     *
     * @param to             신청자 이메일
     * @param userName       신청자 이름
     * @param appSeq         신청서 번호
     * @param documentLabel  승인된 문서 라벨
     */
    void sendDocumentRequestApprovedEmail(String to, String userName, Long appSeq,
                                           String documentLabel);

    /**
     * 서류 반려 알림 (신청자 수신)
     *
     * @param to              신청자 이메일
     * @param userName        신청자 이름
     * @param appSeq          신청서 번호
     * @param documentLabel   반려된 문서 라벨
     * @param rejectionReason 반려 사유 (본문에 이스케이프되어 렌더)
     */
    void sendDocumentRequestRejectedEmail(String to, String userName, Long appSeq,
                                           String documentLabel, String rejectionReason);

    // ── Kaki Concierge Phase 1 PR#2 ──────────────────────

    /**
     * Concierge 계정 활성화 링크 이메일.
     * Kaki Concierge로 자동 생성된 계정에 대해 최초 비밀번호 설정 링크를 발송한다.
     *
     * @param to               수신자 이메일
     * @param fullName         수신자 이름
     * @param setupUrl         완전한 URL (예: https://licensekaki.sg/setup-account/{uuid})
     * @param expiresAtDisplay 만료 시각 표시 문자열 (예: "2026-04-21 16:00 SGT")
     */
    void sendAccountSetupLinkEmail(String to, String fullName, String setupUrl, String expiresAtDisplay);

    /**
     * N1: Concierge 신청 접수 + 계정 설정 링크 (신규 C1 / PENDING C3 케이스).
     * 접수 확인과 활성화 링크를 한 통으로 통합 발송.
     */
    void sendConciergeRequestReceivedEmail(String to, String fullName, String setupUrl, String expiresAtDisplay);

    /**
     * N1-Alt: Concierge 신청 접수 + 이미 활성 계정 연결 안내 (C2 케이스).
     * 활성화 링크는 포함하지 않고 기존 계정 로그인 안내만.
     */
    void sendConciergeRequestReceivedExistingUserEmail(String to, String fullName);

    /**
     * N2: Admin/Concierge Manager에게 신규 신청 접수 알림 (staff-facing).
     */
    void sendConciergeStaffNewRequestEmail(String to, String staffName, String publicCode,
                                            String applicantName, String applicantEmail);

    /**
     * N5-UploadConfirm: Manager가 LOA 서명 파일을 대리 업로드한 후 신청자에게 확인 이메일.
     * 7일 이의 제기 창구 안내 (PRD v1.5 §6.4-3, AC-22b, O-15).
     *
     * @param to             신청자 이메일
     * @param applicantName  신청자 이름
     * @param managerName    업로드를 수행한 Manager 이름
     * @param applicationSeq 신청서 번호
     * @param memo           Manager 수령 경로 메모 (nullable — 없으면 메모 섹션 미노출)
     */
    void sendConciergeLoaUploadConfirmEmail(String to, String applicantName, String managerName,
                                             Long applicationSeq, String memo);

    /**
     * Concierge 견적 이메일 (Phase 1.5) — 통화 후 매니저가 발송.
     * <p>
     * PDPA 최소화: 제목엔 금액·주소·이름 제외, publicCode 만 포함.
     * 피싱 방지: verification phrase 를 본문에 노출하여 통화 내용과 대조 가능.
     * 결제 reference 는 publicCode 를 명시하여 은행 세틀먼트 매칭.
     * <p>
     * 보안 결정: QR 이미지는 본문에 임베드하지 않고 PayNow UEN + 계좌명 텍스트만 제공.
     * 신청자는 publicCode 를 reference 로 입력해 송금 — 모방 메일로 QR 금액·계좌 탈취 차단.
     *
     * @param to                 신청자 이메일
     * @param applicantName      신청자 이름
     * @param publicCode         C-YYYY-NNNN 형식 공개 코드 (이메일 제목·본문·결제 reference)
     * @param quotedAmount       컨시어지 서비스 수수료 (SGD)
     * @param callScheduledAt    통화에서 합의한 후속 일정 (nullable — null 이면 해당 섹션 생략)
     * @param managerNote        매니저가 덧붙일 메모 (nullable)
     * @param verificationPhrase 4단어 피싱 방지 문구 (통화 중 구두 안내된 것과 동일)
     * @param paynowUen          PayNow UEN (system_settings.payment_paynow_uen)
     * @param paynowAccountName  PayNow 수취 계좌명 (system_settings.payment_paynow_name)
     * @return 발송된 이메일의 SMTP Message-ID (감사 로그 조인용, 실패 시 null)
     */
    String sendConciergeQuoteEmail(String to, String applicantName, String publicCode,
                                    BigDecimal quotedAmount, java.time.LocalDateTime callScheduledAt,
                                    String managerNote, String verificationPhrase,
                                    String paynowUen, String paynowAccountName);

    // ── ADMIN Manual Email Dispatch (admin-manual-email-spec.md §8.2, §14) ──────

    /**
     * ADMIN 이 신청자/LEW/외부 수신자에게 발송하는 ad-hoc 수동 이메일.
     *
     * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.2, AC-A13.
     * 본문은 PLAIN_TEXT 만 허용하며, 자동 헤더("This is a manual notice from a LicenseKaki administrator.")
     * + 자동 푸터("Sent by: {adminEmail}" + 표준 반피싱 푸터) 가 시스템에 의해 부착된다.
     * ADMIN 본문은 HTML escape 후 줄바꿈을 {@code <br>} 으로 변환하여 렌더 — XSS 차단.</p>
     *
     * <p><b>예외 정책</b>: SMTP 발송 실패 시 {@link RuntimeException} 을 던진다 (다른 알림 메서드처럼
     * swallow 하지 않음). 호출자({@code ManualEmailDispatchSendListener}) 가 try/catch 로 감싸 row 의
     * {@code dispatchStatus=FAILED} + {@code failedReason} 을 기록할 수 있어야 하기 때문.</p>
     *
     * @param to                  수신자 이메일 주소 (이미 검증/정규화된 값)
     * @param subject             ADMIN 입력 subject (escape 전 원문 — 메일 헤더에 그대로 사용)
     * @param bodyText            ADMIN 입력 PLAIN_TEXT 본문 (escape 전 원문 — 메서드 내부에서 escape)
     * @param adminEmailForFooter 발송 ADMIN 의 이메일 주소 — 자동 푸터의 신원 노출 라인에 표시.
     *                            ADMIN 사칭 위험 완화 (스펙 §9.1 AC-A13).
     */
    void sendManualPlainTextEmail(String to, String subject, String bodyText, String adminEmailForFooter);

    /**
     * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — 영수증 PDF 첨부 이메일 발송.
     *
     * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §8.3, AC-R1.
     * ADMIN/MANAGER 가 별도 수금을 기록한 직후 자동 발행된 Invoice PDF 를 첨부하여 신청자에게
     * 발송한다.</p>
     *
     * <p><b>Subject 정책 (PDPA 최소화)</b>: invoice number 만 노출 (예: {@code [LicenseKaki] Receipt issued · #LK-RCP-20260501-0001}).
     * 금액·이름·주소는 본문에만 — 메일 헤더 캐싱·검색 노출 차단.</p>
     *
     * <p><b>예외 정책</b>: SMTP/IO 실패 시 {@link RuntimeException} 을 던진다 — 호출자
     * ({@code ManualPaymentInvoiceListener}) 가 try/catch 로 감싸 audit 에 INVOICE_AUTO_GENERATED_FROM_MANUAL_PAYMENT
     * 의 실패 status 를 기록하기 위함 (D5=B).</p>
     *
     * @param to                   신청자 이메일
     * @param recipientName        신청자 이름 (escape 후 본문 표시)
     * @param invoiceNumber        영수증 번호 (Subject + 본문)
     * @param amount               결제 금액 (본문 — Subject 미노출)
     * @param currency             통화 코드 (예: SGD)
     * @param attachmentBytes      Invoice PDF 바이트 (null 이면 첨부 생략)
     * @param attachmentFilename   첨부 파일명 (예: {@code INVOICE_LK-RCP-20260501-0001.pdf})
     */
    void sendInvoiceIssuedEmail(String to, String recipientName, String invoiceNumber,
                                 BigDecimal amount, String currency,
                                 byte[] attachmentBytes, String attachmentFilename);

    /**
     * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 가 ConciergeRequest 에 배정되었음을 알리는 이메일.
     *
     * <p>스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §3 S3, §14 PR-3.</p>
     *
     * <p><b>Subject 정책 (PDPA 최소화)</b>: publicCode 만 노출 (예:
     * {@code [LicenseKaki] You have been assigned to a Concierge request · #C-2026-0042}).
     * 신청자 이름/이메일/전화는 본문에만 — 메일 헤더 캐싱·검색 노출 차단.</p>
     *
     * <p><b>본문 구성</b>: 신청자 연락 정보(이름/이메일/전화) + 메모 + 컨시어지 상세 페이지 링크 +
     * 행동 안내("연락 후 신청서 대행 작성 가능") + 표준 반피싱 푸터.</p>
     *
     * <p><b>실패 정책</b>: 다른 알림 메서드와 동일하게 swallow — 본 메서드는 항상 정상 종료해야 하며,
     * 호출자({@code ConciergeLewAssignmentNotificationListener}) 가 별도 try/catch 로 감싸 로그만 남긴다.</p>
     *
     * @param to              LEW 이메일
     * @param lewName         LEW 이름 (인사말, escape 후 사용)
     * @param publicCode      ConciergeRequest 공개 코드 (Subject + CTA URL reference)
     * @param applicantName   신청자 이름 (본문 표기)
     * @param applicantEmail  신청자 이메일 (본문 표기)
     * @param applicantPhone  신청자 전화 (본문 표기)
     * @param memo            컨시어지 폼 메모 (nullable — null/blank 면 섹션 생략)
     * @param reassigned      재할당 케이스 여부 (true 면 안내 문구 추가)
     */
    void sendConciergeLewAssignedEmail(String to, String lewName, String publicCode,
                                         String applicantName, String applicantEmail,
                                         String applicantPhone, String memo,
                                         boolean reassigned);

    /**
     * ★ Concierge 강화 + 별도 수금 PR-3 — 이전에 배정되어 있던 LEW 에게 unassign 통보 이메일.
     *
     * <p>스펙: §10 AC-L4 — 재할당 발생 시 이전 LEW 에게 알림. 본문은 간결히 — 사유는 노출하지 않고,
     * 추가 작업이 불필요함만 안내 (PDPA 최소화 + 사칭 방지). 자세한 컨텍스트는 매니저 측 채널로.</p>
     *
     * @param to               이전 LEW 이메일
     * @param lewName          이전 LEW 이름
     * @param publicCode       ConciergeRequest 공개 코드
     */
    void sendConciergeLewUnassignedEmail(String to, String lewName, String publicCode);

    /**
     * PR-3: ADMIN 수동 이메일 미리보기용 HTML 렌더러.
     *
     * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5.4. 실제 SMTP 발송 없이
     * {@link #sendManualPlainTextEmail} 과 동일한 자동 헤더("This is a manual notice...") + ADMIN
     * 신원 푸터("Sent by: ...") + 표준 반피싱 푸터를 부착해 ADMIN 이 발송 전 모양을 모달로 확인할 수
     * 있게 한다. DB 영향 0, 트랜잭션 없음.</p>
     *
     * <p>본문은 {@code HtmlUtils.htmlEscape} 로 XSS 차단 → 줄바꿈만 {@code <br>} 로 변환. 모든 구현체
     * (SMTP/LogOnly) 는 동일 결과를 반환해야 한다 — 미리보기는 환경에 따라 다르면 안 된다.</p>
     *
     * @param subject             ADMIN 입력 subject (현재는 본문 HTML 에 직접 노출되지는 않으나,
     *                            향후 헤더 라인 등에 사용할 수 있도록 시그니처에 포함).
     * @param bodyText            ADMIN 입력 PLAIN_TEXT 본문 (escape 전 원문)
     * @param adminEmailForFooter 발송 ADMIN 의 이메일 주소 — 푸터 신원 표시
     * @return 안전하게 렌더된 HTML 문자열 (iframe sandbox 또는 dangerouslySetInnerHTML 로 주입 가능)
     */
    String renderManualPlainTextHtml(String subject, String bodyText, String adminEmailForFooter);

    // ── PR-0C: Generic notification email (NotificationChannelAdapter 패턴) ──────

    /**
     * Generic 알림 발송 — 새 {@code NotificationChannelAdapter} 패턴이 사용 (PR-0C).
     *
     * <p>기존 {@code sendXxx} specific 메서드들은 점진 마이그레이션을 거쳐 본 메서드 위로 흡수된다.
     * 본문은 호출 측({@code NotificationTemplateRegistry}) 이 이미 변수 치환 + HTML 안전화를 마친
     * 완성된 HTML 이어야 한다 — 본 메서드는 단순 전송 책임만 가진다.</p>
     *
     * <p><b>예외 정책</b>: {@link #sendManualPlainTextEmail} 과 동일하게 SMTP 실패 시
     * {@link RuntimeException} 을 던진다. 호출자({@code EmailChannelAdapter}) 가 try/catch 로 감싸
     * outbox row 의 상태(FAILED/DEAD) 갱신에 사용한다.</p>
     *
     * @param to       수신자 이메일 주소 (검증/정규화된 값)
     * @param subject  메시지 제목 (이미 변수 치환 완료)
     * @param htmlBody 메시지 본문 HTML (이미 변수 치환 + 안전화 완료)
     */
    void sendGenericEmail(String to, String subject, String htmlBody);
}
