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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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
                """.formatted(userName, resetLink, resetLink, resetLink);
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
                """.formatted(userName, urgencyColor, daysText, licenseNumber, address, urgencyColor, formattedDate);
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
                """.formatted(userName, verificationLink, verificationLink, verificationLink);
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
                """.formatted(userName, appSeq, address, comment != null ? comment : "Please review and update your application.");
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
                """.formatted(userName, appSeq, address, amount);
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
                """.formatted(userName, amount, appSeq, address);
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
                """.formatted(userName, appSeq, licenseNo, address, formattedDate);
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
                """.formatted(lewName, appSeq, address, applicantName);
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
                """.formatted(lewName, amount, appSeq, address, amount);
    }
}
