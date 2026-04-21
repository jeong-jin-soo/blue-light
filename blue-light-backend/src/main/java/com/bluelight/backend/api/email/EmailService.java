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
}
