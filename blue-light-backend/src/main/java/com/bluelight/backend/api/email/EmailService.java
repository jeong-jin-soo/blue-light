package com.bluelight.backend.api.email;

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
}
