package com.bluelight.backend.api.email;

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
}
