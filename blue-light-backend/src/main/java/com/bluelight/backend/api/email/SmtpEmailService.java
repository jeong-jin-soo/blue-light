package com.bluelight.backend.api.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SMTP 기반 이메일 발송 서비스
 * - mail.smtp.enabled=true 일 때만 활성화
 * - AWS SES, Gmail SMTP 등 모든 SMTP 서버와 호환
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mail.smtp.enabled", havingValue = "true")
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.sender.from:noreply@licensekaki.com}")
    private String fromAddress;

    @Value("${spring.mail.sender.name:LicenseKaki}")
    private String fromName;

    /**
     * 프론트엔드 base URL — CTA 링크 및 로고 이미지 참조에 사용.
     * {@code password-reset.base-url} 를 재사용하여 별도 설정 부담을 줄인다.
     */
    @Value("${password-reset.base-url:http://localhost:5174}")
    private String appBaseUrl;

    // ── B-2 · HTML 이스케이프 유틸 ─────────────────────────
    // 모든 사용자 입력 주입 지점(userName, comment, rejectionReason, customLabel 등)에 적용.
    // 기존 템플릿도 회귀 방지 차원에서 동일 유틸을 통과시킨다.
    private static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    @Override
    @Async
    public void sendPasswordResetEmail(String to, String userName, String resetLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Reset Your Password - LicenseKaki");

            String htmlContent = buildPasswordResetHtml(userName, resetLink);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Password reset email sent to: {}", to);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send password reset email to: {}", to, e);
            // 이메일 발송 실패해도 예외를 던지지 않음 (보안: 이메일 존재 여부 노출 방지)
        }
    }

    @Override
    @Async
    public void sendEmailVerificationEmail(String to, String userName, String verificationLink) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Verify Your Email - LicenseKaki");

            String htmlContent = buildEmailVerificationHtml(userName, verificationLink);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Email verification email sent to: {}", to);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email verification email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendLicenseExpiryWarningEmail(String to, String userName,
                                               String licenseNumber, String address,
                                               LocalDate expiryDate, int daysRemaining) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Licence Expiry Notice - LicenseKaki");

            String htmlContent = buildLicenseExpiryHtml(userName, licenseNumber, address, expiryDate, daysRemaining);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("License expiry warning email sent to: {}", to);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send license expiry warning email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendRevisionRequestEmail(String to, String userName, Long appSeq, String address, String comment) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Revision Requested - Application #" + appSeq + " - LicenseKaki");
            helper.setText(buildRevisionRequestHtml(userName, appSeq, address, comment), true);
            mailSender.send(message);
            log.info("Revision request email sent to: {}, appSeq={}", to, appSeq);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send revision request email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendPaymentRequestEmail(String to, String userName, Long appSeq, String address, BigDecimal amount) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Payment Required - Application #" + appSeq + " - LicenseKaki");
            helper.setText(buildPaymentRequestHtml(userName, appSeq, address, amount), true);
            mailSender.send(message);
            log.info("Payment request email sent to: {}, appSeq={}", to, appSeq);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send payment request email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendPaymentConfirmEmail(String to, String userName, Long appSeq, String address, BigDecimal amount) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Payment Confirmed - Application #" + appSeq + " - LicenseKaki");
            helper.setText(buildPaymentConfirmHtml(userName, appSeq, address, amount), true);
            mailSender.send(message);
            log.info("Payment confirm email sent to: {}, appSeq={}", to, appSeq);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send payment confirm email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendLicenseIssuedEmail(String to, String userName, Long appSeq,
                                        String address, String licenseNo, LocalDate expiryDate) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Licence Issued - " + licenseNo + " - LicenseKaki");
            helper.setText(buildLicenseIssuedHtml(userName, appSeq, address, licenseNo, expiryDate), true);
            mailSender.send(message);
            log.info("License issued email sent to: {}, appSeq={}, licenseNo={}", to, appSeq, licenseNo);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send license issued email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendLewAssignedEmail(String to, String lewName, Long appSeq, String address, String applicantName) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("New Application Assigned - #" + appSeq + " - LicenseKaki");
            helper.setText(buildLewAssignedHtml(lewName, appSeq, address, applicantName), true);
            mailSender.send(message);
            log.info("LEW assigned email sent to: {}, appSeq={}", to, appSeq);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send LEW assigned email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendPaymentConfirmedToLewEmail(String to, String lewName, Long appSeq, String address, BigDecimal amount) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("Payment Confirmed for Application #" + appSeq + " - LicenseKaki");
            helper.setText(buildPaymentConfirmedToLewHtml(lewName, appSeq, address, amount), true);
            mailSender.send(message);
            log.info("Payment confirmed (LEW) email sent to: {}, appSeq={}", to, appSeq);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send payment confirmed (LEW) email to: {}", to, e);
        }
    }

    // ── HTML 템플릿 빌더 ──────────────────────

    private String buildPasswordResetHtml(String userName, String resetLink) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
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
                """.formatted(esc(userName), esc(resetLink), esc(resetLink), esc(resetLink));
    }

    private String buildLicenseExpiryHtml(String userName, String licenseNumber,
                                           String address, LocalDate expiryDate, int daysRemaining) {
        String formattedDate = expiryDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        String urgencyColor = daysRemaining <= 7 ? "#dc2626" : daysRemaining <= 14 ? "#f59e0b" : "#1a3a5c";
        String daysText = daysRemaining <= 0
                ? "Your licence has expired."
                : "Your licence will expire in <strong>" + daysRemaining + " day" + (daysRemaining == 1 ? "" : "s") + "</strong>.";

        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Licence Expiry Notice</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: %s; line-height: 1.6; font-size: 16px;">
                        %s
                      </p>
                      <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin: 24px 0;">
                        <table style="width: 100%%; font-size: 14px; color: #555555;">
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Licence No.</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Address</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Expiry Date</td>
                            <td style="padding: 6px 0; color: %s; font-weight: bold;">%s</td>
                          </tr>
                        </table>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        To continue operating your electrical installation, please submit a renewal application
                        before the expiry date. You can start the renewal process by logging into your LicenseKaki account.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki. If you have already renewed your licence, please disregard this email.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(userName), urgencyColor, daysText, esc(licenseNumber), esc(address), urgencyColor, formattedDate);
    }

    private String buildEmailVerificationHtml(String userName, String verificationLink) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Verify Your Email</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        Thank you for signing up with LicenseKaki. Please verify your email address by clicking the button below.
                      </p>
                      <div style="text-align: center; margin: 32px 0;">
                        <a href="%s" style="display: inline-block; background-color: #1a3a5c; color: #ffffff; text-decoration: none; padding: 14px 32px; border-radius: 6px; font-weight: bold; font-size: 16px;">
                          Verify Email
                        </a>
                      </div>
                      <p style="color: #888888; font-size: 13px; line-height: 1.5;">
                        If you didn't create an account with LicenseKaki, you can safely ignore this email.
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
                """.formatted(esc(userName), esc(verificationLink), esc(verificationLink), esc(verificationLink));
    }

    private String buildRevisionRequestHtml(String userName, Long appSeq, String address, String comment) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Revision Requested</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        Your application <strong>#%d</strong> for <strong>%s</strong> requires some revisions before it can proceed.
                      </p>
                      <div style="background-color: #fef3c7; border: 1px solid #f59e0b; border-radius: 8px; padding: 16px; margin: 24px 0;">
                        <p style="color: #92400e; margin: 0; font-weight: bold; font-size: 13px;">REVIEWER COMMENT</p>
                        <p style="color: #78350f; margin: 8px 0 0 0; line-height: 1.5;">%s</p>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        Please log in to your LicenseKaki account to review the comments and update your application.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                        esc(userName),
                        appSeq,
                        esc(address),
                        esc(comment != null ? comment : "Please review and update your application."));
    }

    private String buildPaymentRequestHtml(String userName, Long appSeq, String address, BigDecimal amount) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Payment Required</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        Your application <strong>#%d</strong> for <strong>%s</strong> has been reviewed and approved.
                        Please proceed with the payment to continue the licensing process.
                      </p>
                      <div style="background-color: #f0fdf4; border: 1px solid #22c55e; border-radius: 8px; padding: 20px; margin: 24px 0; text-align: center;">
                        <p style="color: #166534; margin: 0; font-size: 14px;">Amount Due</p>
                        <p style="color: #166534; margin: 8px 0 0 0; font-size: 28px; font-weight: bold;">$%s</p>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        Please log in to your LicenseKaki account to view payment instructions and upload your payment proof.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(userName), appSeq, esc(address), amount);
    }

    private String buildPaymentConfirmHtml(String userName, Long appSeq, String address, BigDecimal amount) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Payment Confirmed</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        We have received and confirmed your payment of <strong>$%s</strong> for application
                        <strong>#%d</strong> at <strong>%s</strong>.
                      </p>
                      <div style="background-color: #f0fdf4; border: 1px solid #22c55e; border-radius: 8px; padding: 16px; margin: 24px 0; text-align: center;">
                        <p style="color: #166534; margin: 0; font-size: 16px; font-weight: bold;">&#10003; Payment Successful</p>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        Your application is now being processed. We will notify you once your licence has been issued.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(userName), amount, appSeq, esc(address));
    }

    private String buildLicenseIssuedHtml(String userName, Long appSeq, String address,
                                           String licenseNo, LocalDate expiryDate) {
        String formattedDate = expiryDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #16a34a; margin-top: 0;">Licence Issued!</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        Congratulations! Your electrical installation licence has been issued for application <strong>#%d</strong>.
                      </p>
                      <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin: 24px 0;">
                        <table style="width: 100%%; font-size: 14px; color: #555555;">
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Licence No.</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Address</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Expiry Date</td>
                            <td style="padding: 6px 0; font-weight: bold;">%s</td>
                          </tr>
                        </table>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        Please remember to renew your licence before the expiry date to avoid any disruptions.
                        You can view your licence details by logging into your LicenseKaki account.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(userName), appSeq, esc(licenseNo), esc(address), formattedDate);
    }

    private String buildLewAssignedHtml(String lewName, Long appSeq, String address, String applicantName) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">New Application Assigned</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        A new application has been assigned to you for review.
                      </p>
                      <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin: 24px 0;">
                        <table style="width: 100%%; font-size: 14px; color: #555555;">
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Application</td>
                            <td style="padding: 6px 0;">#%d</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Address</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Applicant</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                        </table>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        Please log in to your LicenseKaki account to review the application and take necessary actions.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(lewName), appSeq, esc(address), esc(applicantName));
    }

    private String buildPaymentConfirmedToLewHtml(String lewName, Long appSeq, String address, BigDecimal amount) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f4f6f9; margin: 0; padding: 20px;">
                  <div style="max-width: 600px; margin: 0 auto; background-color: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">
                    <div style="background-color: #1a3a5c; padding: 24px; text-align: center;">
                      <h1 style="color: #ffffff; margin: 0; font-size: 24px;">LicenseKaki</h1>
                    </div>
                    <div style="padding: 32px 24px;">
                      <h2 style="color: #333333; margin-top: 0;">Payment Confirmed</h2>
                      <p style="color: #555555; line-height: 1.6;">Hello %s,</p>
                      <p style="color: #555555; line-height: 1.6;">
                        Payment of <strong>$%s</strong> has been confirmed for an application assigned to you.
                      </p>
                      <div style="background-color: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 20px; margin: 24px 0;">
                        <table style="width: 100%%; font-size: 14px; color: #555555;">
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Application</td>
                            <td style="padding: 6px 0;">#%d</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Address</td>
                            <td style="padding: 6px 0;">%s</td>
                          </tr>
                          <tr>
                            <td style="padding: 6px 0; font-weight: bold;">Amount</td>
                            <td style="padding: 6px 0;">$%s</td>
                          </tr>
                        </table>
                      </div>
                      <p style="color: #555555; line-height: 1.6;">
                        You can now proceed with processing this application. Please log in to your LicenseKaki account to continue.
                      </p>
                      <hr style="border: none; border-top: 1px solid #eee; margin: 24px 0;">
                      <p style="color: #aaaaaa; font-size: 12px;">
                        This is an automated notification from LicenseKaki.
                      </p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(esc(lewName), amount, appSeq, esc(address), amount);
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 3 PR#4 · LEW Document Request Workflow — 4종 이메일
    // ──────────────────────────────────────────────────────────────────

    @Override
    @Async
    public void sendDocumentRequestCreatedEmail(String to, String userName, Long appSeq,
                                                 int requestedCount, List<String> documentLabels) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("[LicenseKaki] Your LEW requested " + requestedCount
                    + " document(s) · LEW가 서류를 요청했습니다");
            helper.setText(buildDocumentRequestCreatedHtml(userName, appSeq, requestedCount, documentLabels), true);
            mailSender.send(message);
            log.info("Document request created email sent to: {}, appSeq={}, count={}", to, appSeq, requestedCount);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send document request created email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendDocumentRequestFulfilledEmail(String to, String lewName, Long appSeq, String documentLabel) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            // PDPA: 신청자 이름은 제외하고 appSeq/라벨만 노출 (B-2 §3.1 권고)
            helper.setSubject("[LicenseKaki] Application #" + appSeq
                    + " — applicant uploaded " + documentLabel + ", please review");
            helper.setText(buildDocumentRequestFulfilledHtml(lewName, appSeq, documentLabel), true);
            mailSender.send(message);
            log.info("Document request fulfilled email sent to: {}, appSeq={}, label={}", to, appSeq, documentLabel);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send document request fulfilled email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendDocumentRequestApprovedEmail(String to, String userName, Long appSeq, String documentLabel) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("[LicenseKaki] " + documentLabel + " approved · " + documentLabel + " 승인됨");
            helper.setText(buildDocumentRequestApprovedHtml(userName, appSeq, documentLabel), true);
            mailSender.send(message);
            log.info("Document request approved email sent to: {}, appSeq={}, label={}", to, appSeq, documentLabel);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send document request approved email to: {}", to, e);
        }
    }

    @Override
    @Async
    public void sendDocumentRequestRejectedEmail(String to, String userName, Long appSeq,
                                                  String documentLabel, String rejectionReason) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, fromName);
            helper.setTo(to);
            helper.setSubject("[LicenseKaki] " + documentLabel + " needs re-upload · "
                    + documentLabel + " 재업로드 필요");
            helper.setText(buildDocumentRequestRejectedHtml(userName, appSeq, documentLabel, rejectionReason), true);
            mailSender.send(message);
            log.info("Document request rejected email sent to: {}, appSeq={}, label={}", to, appSeq, documentLabel);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send document request rejected email to: {}", to, e);
        }
    }

    // ── Phase 3 템플릿 빌더 (영/한 병기, primary #1a3a5c 헤더, PDPA 푸터) ──

    /**
     * Phase 3 공통 레이아웃. {@code coreEn}/{@code coreKo} 타이틀, 상세 블록, CTA, PDPA 푸터를 렌더.
     * 동적 값은 모두 호출부에서 {@link #esc} 를 거친 뒤 주입한다.
     */
    private String buildDocumentEmailLayout(String coreEn, String coreKo, String detailsHtml,
                                             String deepLinkPath, String role) {
        String deepLink = appBaseUrl + deepLinkPath;
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; background-color: #f3f4f6; margin: 0; padding: 24px 0;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:#f3f4f6;">
                    <tr><td align="center">
                      <table width="600" style="max-width:600px;background:#ffffff;border-radius:12px;overflow:hidden;box-shadow: 0 2px 8px rgba(0,0,0,0.05);">
                        <tr><td style="background:#1a3a5c;padding:20px 32px;">
                          <h1 style="color:#ffffff;margin:0;font-size:20px;font-weight:600;">LicenseKaki</h1>
                        </td></tr>
                        <tr><td style="padding:32px;color:#1f2937;">
                          <h2 style="font-size:18px;margin:0 0 8px;color:#111827;">%s</h2>
                          <p style="font-size:14px;color:#6b7280;font-style:italic;margin:0 0 20px;">%s</p>
                          <table width="100%%" style="background:#f9fafb;border-radius:8px;margin-bottom:24px;">
                            <tr><td style="padding:16px;font-size:13px;color:#374151;line-height:1.8;">
                              %s
                            </td></tr>
                          </table>
                          <a href="%s" style="display:inline-block;background:#1a3a5c;color:#ffffff;text-decoration:none;padding:12px 24px;border-radius:8px;font-weight:600;font-size:14px;">Open in LicenseKaki &rarr;</a>
                        </td></tr>
                        <tr><td style="background:#f9fafb;padding:16px 32px;font-size:11px;color:#9ca3af;line-height:1.6;">
                          You are receiving this because you are the %s on this application.<br/>
                          LicenseKaki complies with Singapore PDPA. For privacy inquiries: privacy@licensekaki.sg
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(esc(coreEn), esc(coreKo), detailsHtml, esc(deepLink), esc(role));
    }

    private String buildDocumentRequestCreatedHtml(String userName, Long appSeq,
                                                    int requestedCount, List<String> documentLabels) {
        StringBuilder labelList = new StringBuilder();
        if (documentLabels != null && !documentLabels.isEmpty()) {
            labelList.append("<strong>Documents requested:</strong><ul style=\"margin:4px 0 0 18px;padding:0;\">");
            for (String label : documentLabels) {
                labelList.append("<li>").append(esc(label)).append("</li>");
            }
            labelList.append("</ul>");
        }
        String details = """
                <strong>Application:</strong> #%d<br/>
                <strong>Hello,</strong> %s<br/>
                Your LEW has requested <strong>%d</strong> document(s) to proceed with your licence application. Please log in to upload them.<br/>
                %s
                """.formatted(appSeq, esc(userName), requestedCount, labelList.toString());
        return buildDocumentEmailLayout(
                "Your LEW has requested documents",
                "서류 요청이 도착했습니다",
                details,
                "/applications/" + appSeq,
                "applicant");
    }

    private String buildDocumentRequestFulfilledHtml(String lewName, Long appSeq, String documentLabel) {
        String details = """
                <strong>Application:</strong> #%d<br/>
                <strong>Hello,</strong> %s<br/>
                The applicant has uploaded <strong>%s</strong>. Please review it in LicenseKaki.
                """.formatted(appSeq, esc(lewName), esc(documentLabel));
        return buildDocumentEmailLayout(
                "Applicant uploaded a requested document",
                "신청자가 요청 서류를 업로드했습니다",
                details,
                "/admin/applications/" + appSeq,
                "assigned LEW");
    }

    private String buildDocumentRequestApprovedHtml(String userName, Long appSeq, String documentLabel) {
        String details = """
                <strong>Application:</strong> #%d<br/>
                <strong>Document:</strong> %s<br/>
                <strong>Hello,</strong> %s<br/>
                Your document has been <span style="color:#16a34a;font-weight:600;">approved</span>. No further action is required for this item.
                """.formatted(appSeq, esc(documentLabel), esc(userName));
        return buildDocumentEmailLayout(
                documentLabel + " approved",
                documentLabel + " 승인됨",
                details,
                "/applications/" + appSeq,
                "applicant");
    }

    private String buildDocumentRequestRejectedHtml(String userName, Long appSeq,
                                                     String documentLabel, String rejectionReason) {
        String details = """
                <strong>Application:</strong> #%d<br/>
                <strong>Document:</strong> %s<br/>
                <strong>Hello,</strong> %s<br/>
                Your document needs to be <span style="color:#dc2626;font-weight:600;">re-uploaded</span>. Please review the reviewer's reason below and upload a corrected file.
                <div style="background:#fef3c7;border-left:3px solid #f59e0b;padding:12px;margin-top:12px;border-radius:4px;">
                  <strong style="color:#92400e;">Reason:</strong>
                  <p style="color:#78350f;margin:4px 0 0 0;line-height:1.5;">%s</p>
                </div>
                """.formatted(appSeq, esc(documentLabel), esc(userName), esc(rejectionReason));
        return buildDocumentEmailLayout(
                documentLabel + " needs re-upload",
                documentLabel + " 재업로드 필요",
                details,
                "/applications/" + appSeq,
                "applicant");
    }
}
