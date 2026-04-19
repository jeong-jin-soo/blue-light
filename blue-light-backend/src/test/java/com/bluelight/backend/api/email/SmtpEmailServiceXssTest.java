package com.bluelight.backend.api.email;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.mail.Session;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 PR#4 — B-2 XSS 방어 회귀 테스트.
 *
 * <p>LEW 가 입력하는 rejectionReason / lewNote / customLabel 등 사용자 입력이
 * 이메일 HTML 에 그대로 삽입되지 않고 {@code HtmlUtils.htmlEscape} 를 거치는지 검증.
 */
class SmtpEmailServiceXssTest {

    private JavaMailSender mailSender;
    private SmtpEmailService service;
    private MimeMessage message;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        message = new MimeMessage(session);
        when(mailSender.createMimeMessage()).thenReturn(message);

        service = new SmtpEmailService(mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@licensekaki.com");
        ReflectionTestUtils.setField(service, "fromName", "LicenseKaki");
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:5174");
    }

    private String htmlBodyOf(MimeMessage mm) throws Exception {
        // MimeMultipart/alternative 트리를 펼쳐 text/html 파트를 반환.
        Object content = mm.getContent();
        return extractHtml(content);
    }

    private String extractHtml(Object content) throws Exception {
        if (content instanceof String s) return s;
        if (content instanceof jakarta.mail.Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                String html = extractHtml(mp.getBodyPart(i).getContent());
                if (html != null && html.contains("<")) return html;
            }
        }
        return "";
    }

    @Test
    void 반려사유_스크립트_태그는_이스케이프되어_본문에_삽입된다() throws Exception {
        String payload = "<script>alert('xss')</script>";

        service.sendDocumentRequestRejectedEmail(
                "applicant@example.com", "John", 42L, "LOA", payload);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String html = htmlBodyOf(captor.getValue());

        // 원본 스크립트 태그는 렌더되지 않고 엔티티 형태로만 존재해야 함
        assertThat(html).doesNotContain("<script>alert('xss')</script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void 문서라벨에_삽입된_HTML_도_이스케이프된다() throws Exception {
        String evilLabel = "<img src=x onerror=alert(1)>";

        service.sendDocumentRequestApprovedEmail(
                "applicant@example.com", "John", 42L, evilLabel);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String html = htmlBodyOf(captor.getValue());

        assertThat(html).doesNotContain("<img src=x onerror=alert(1)>");
        assertThat(html).contains("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void 생성_이메일_제목은_요청개수를_포함한다() throws Exception {
        service.sendDocumentRequestCreatedEmail(
                "applicant@example.com", "Alice", 99L, 3,
                List.of("LOA", "SP_ACCOUNT", "<evil>"));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).contains("3 document(s)");
        assertThat(sent.getSubject()).contains("Your LEW requested");

        // 라벨 리스트의 HTML 도 이스케이프되는지
        String html = htmlBodyOf(sent);
        assertThat(html).doesNotContain("<evil>");
        assertThat(html).contains("&lt;evil&gt;");
    }

    @Test
    void 기존_리비전_요청_이메일의_comment도_이스케이프된다() throws Exception {
        // 회귀 방지: 기존 메서드가 esc() 로 정리되었는지 검증
        service.sendRevisionRequestEmail(
                "applicant@example.com", "Bob", 7L, "123 Raffles Place",
                "<script>bad()</script>");

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        String html = htmlBodyOf(captor.getValue());

        assertThat(html).doesNotContain("<script>bad()</script>");
        assertThat(html).contains("&lt;script&gt;bad()&lt;/script&gt;");
    }
}
