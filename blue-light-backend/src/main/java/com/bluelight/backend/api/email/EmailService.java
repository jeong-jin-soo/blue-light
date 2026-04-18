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
}
