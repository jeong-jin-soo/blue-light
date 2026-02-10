package com.bluelight.backend.api.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * SMTP 기반 이메일 발송 서비스
 * - spring.mail.host 설정이 있을 때만 활성화
 * - AWS SES, Gmail SMTP 등 모든 SMTP 서버와 호환
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(JavaMailSender.class)
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.sender.from:noreply@bluelight.sg}")
    private String fromAddress;

    @Value("${spring.mail.sender.name:Blue Light}")
    private String fromName;

    @Override
    @Async
    public void sendPasswordResetEmail(String to, String userName, String resetLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Reset Your Password - Blue Light");

            String htmlContent = buildPasswordResetHtml(userName, resetLink);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password reset email sent to: {}", to);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send password reset email to: {}", to, e);
            // 이메일 발송 실패해도 예외를 던지지 않음 (보안: 이메일 존재 여부 노출 방지)
        }
    }

    private String buildPasswordResetHtml(String userName, String resetLink) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">Blue Light</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Reset Your Password</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        We received a request to reset your password. Click the button below to create a new password.
                        This link will expire in 1 hour.
                      </p>
                      <div style="text-align: center; margin: 32px 0;">
                        <a href="%s" style="display: inline-block; background-color: #1a3a5c; color: #ffffff; text-decoration: none; padding: 14px 32px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                          Reset Password
                        </a>
                      </div>
                      <p style="color: #888888; font-size: 13px; line-height: 1.5;">
                        If you didn't request a password reset, you can safely ignore this email.
                        Your password will remain unchanged.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        If the button doesn't work, copy and paste this link into your browser:<br>
                        <a href="%s" style="color: #1a3a5c;">%s</a>
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(userName, resetLink, resetLink, resetLink);
    }
}
