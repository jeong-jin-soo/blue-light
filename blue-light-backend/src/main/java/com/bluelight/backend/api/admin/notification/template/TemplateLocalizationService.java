package com.bluelight.backend.api.admin.notification.template;

import com.bluelight.backend.api.admin.notification.template.dto.ImportReportResponse;
import com.bluelight.backend.api.admin.notification.template.dto.LocalizationFormat;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateDraft;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PR-T7 P1 — 외주 LSP(Localization Service Provider) 라운드용 export/import.
 *
 * <p>스펙: {@code doc/Project Analysis/notification-template-manager-spec.md} §10.2.</p>
 *
 * <p>흐름:
 * <ol>
 *   <li>운영자가 base locale (en) export → XLIFF/CSV 파일 다운로드</li>
 *   <li>외주 LSP 가 번역 → 채워진 파일 반환</li>
 *   <li>운영자가 target locale (ko/zh-Hans) 지정해서 import → 행마다 draft 생성 (PENDING)</li>
 *   <li>SYSTEM_ADMIN 이 Draft 리뷰 큐에서 일괄 approve → publish</li>
 * </ol>
 * </p>
 *
 * <p><b>Trans-unit id 규칙</b>: {@code "{templateCode}|{channel}|{field}"} (field: subject 또는 body).
 * SMS 등 subject 가 없는 row 는 subject trans-unit 생략. 동일 (code, channel) 의 subject + body
 * 는 합쳐서 하나의 draft 로 만든다.</p>
 *
 * <p><b>변수 일관성</b>: import 시 base (en) 와 target 의 변수 집합이 일치하는지 검증.
 * 다르면 해당 (code, channel) 은 FAILED 로 리포트하고 draft 생성하지 않음. NM 의 lint(L1)
 * 와 동일 정책 (스펙 §10.1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateLocalizationService {

    private static final String BASE_LOCALE = "en";
    private static final String TRANS_UNIT_ID_SEPARATOR = "|";
    private static final String FIELD_SUBJECT = "subject";
    private static final String FIELD_BODY = "body";

    /** XLIFF 1.2 namespace — LSP 표준. */
    private static final String XLIFF_NS = "urn:oasis:names:tc:xliff:document:1.2";

    /** {@code \{\{varName\}\}} — Mustache-like 변수 추출 정규식. lint L1 과 동일. */
    private static final java.util.regex.Pattern VAR_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    private final NotificationTemplateRepository templateRepository;
    private final NotificationTemplateAdminService adminService;

    // ============================================================
    // Export
    // ============================================================

    /**
     * 지정 locale 의 활성 template 들을 XLIFF/CSV 로 직렬화.
     *
     * <p>target language 는 사용자가 import 시 결정하므로 export 단계에서는 미정.
     * 본 구현은 source-language={baseLocale}, target-language="__TBD__" 로 둔 placeholder
     * 를 사용하며, 외주 LSP 가 target-language 를 채워서 반환하거나 사용자가 import 파라미터
     * 로 명시한다.</p>
     */
    public byte[] export(String locale, LocalizationFormat format) {
        String baseLocale = locale != null ? locale : BASE_LOCALE;
        List<NotificationTemplate> rows = templateRepository
                .findByLocaleAndEnabledOrderByTemplateCodeAscChannelAsc(baseLocale, true);
        log.info("Localization export: locale={}, format={}, rows={}", baseLocale, format, rows.size());
        return switch (format) {
            case XLIFF -> serializeXliff(baseLocale, rows);
            case CSV -> serializeCsv(rows);
        };
    }

    private byte[] serializeXliff(String sourceLocale, List<NotificationTemplate> rows) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE 방지 (security-review 권고 — 외부 LSP 응답이라도 untrusted)
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();

            Element xliff = doc.createElementNS(XLIFF_NS, "xliff");
            xliff.setAttribute("version", "1.2");
            doc.appendChild(xliff);

            Element file = doc.createElementNS(XLIFF_NS, "file");
            file.setAttribute("source-language", sourceLocale);
            file.setAttribute("target-language", "__TBD__");
            file.setAttribute("datatype", "plaintext");
            file.setAttribute("original", "notification-templates");
            xliff.appendChild(file);

            Element body = doc.createElementNS(XLIFF_NS, "body");
            file.appendChild(body);

            for (NotificationTemplate row : rows) {
                if (row.getSubject() != null && !row.getSubject().isBlank()) {
                    body.appendChild(buildTransUnit(doc, row, FIELD_SUBJECT, row.getSubject()));
                }
                body.appendChild(buildTransUnit(doc, row, FIELD_BODY, row.getBodyText()));
            }

            return writeDocument(doc);
        } catch (ParserConfigurationException | TransformerException ex) {
            throw new LocalizationException("Failed to build XLIFF: " + ex.getMessage(), ex);
        }
    }

    private Element buildTransUnit(Document doc, NotificationTemplate row, String field, String source) {
        Element transUnit = doc.createElementNS(XLIFF_NS, "trans-unit");
        transUnit.setAttribute("id", row.getTemplateCode() + TRANS_UNIT_ID_SEPARATOR
                + row.getChannel().name() + TRANS_UNIT_ID_SEPARATOR + field);
        Element src = doc.createElementNS(XLIFF_NS, "source");
        src.setTextContent(source);
        transUnit.appendChild(src);
        Element tgt = doc.createElementNS(XLIFF_NS, "target");
        tgt.setTextContent("");
        transUnit.appendChild(tgt);
        return transUnit;
    }

    private byte[] writeDocument(Document doc) throws TransformerException {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toByteArray();
    }

    private byte[] serializeCsv(List<NotificationTemplate> rows) {
        StringBuilder sb = new StringBuilder();
        // BOM 으로 Excel 호환 (CP-949 자동인식 방지)
        sb.append('﻿');
        sb.append("template_code,channel,locale,subject,body_text\n");
        for (NotificationTemplate row : rows) {
            sb.append(csvField(row.getTemplateCode())).append(',');
            sb.append(csvField(row.getChannel().name())).append(',');
            sb.append(csvField(row.getLocale())).append(',');
            sb.append(csvField(row.getSubject())).append(',');
            sb.append(csvField(row.getBodyText())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** RFC 4180 — quote 로 감싸고 내부 quote 는 더블 quote 로 escape. */
    private static String csvField(String value) {
        if (value == null) return "";
        boolean needsQuote = value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r");
        if (!needsQuote) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    // ============================================================
    // Import
    // ============================================================

    /**
     * XLIFF/CSV 업로드 → 행마다 draft 생성 (PENDING).
     *
     * <p>각 (code, channel) 페어에 대해:
     * <ol>
     *   <li>base (locale=en) template 조회 — 없으면 SKIPPED</li>
     *   <li>변수 일관성 검증 (target 의 {{var}} ⊂ base 의 {{var}}) — 위반 시 FAILED</li>
     *   <li>{@link NotificationTemplateAdminService#createDraft} 호출 → PENDING draft</li>
     * </ol>
     * </p>
     *
     * <p>한 행이라도 트랜잭션 전체를 rollback 시키지 않는다 — 부분 성공 허용 (REQUIRES_NEW).
     * 운영자가 실패 행만 수정 후 재업로드 할 수 있어야 한다.</p>
     */
    @Transactional
    public ImportReportResponse importTemplates(String targetLocale, LocalizationFormat format,
                                                InputStream inputStream, Long actorUserSeq) {
        if (targetLocale == null || targetLocale.isBlank()) {
            throw new LocalizationException("targetLocale is required");
        }
        if (BASE_LOCALE.equals(targetLocale)) {
            throw new LocalizationException(
                    "Cannot import into base locale '" + BASE_LOCALE + "' — editing base requires Draft UI");
        }

        List<ParsedUnit> units = switch (format) {
            case XLIFF -> parseXliff(inputStream);
            case CSV -> parseCsv(inputStream);
        };

        // 같은 (code, channel) 페어로 그룹화 — XLIFF 는 subject/body 가 별도 trans-unit
        Map<TemplateKey, AggregatedUnit> grouped = new LinkedHashMap<>();
        for (ParsedUnit u : units) {
            TemplateKey key = new TemplateKey(u.code(), u.channel());
            grouped.computeIfAbsent(key, k -> new AggregatedUnit()).merge(u);
        }

        log.info("Localization import: locale={}, format={}, units={}, groups={}",
                targetLocale, format, units.size(), grouped.size());

        List<ImportReportResponse.Item> items = new ArrayList<>(grouped.size());
        int created = 0, skipped = 0, failed = 0;

        for (Map.Entry<TemplateKey, AggregatedUnit> entry : grouped.entrySet()) {
            TemplateKey key = entry.getKey();
            AggregatedUnit agg = entry.getValue();

            try {
                NotificationChannel channel;
                try {
                    channel = NotificationChannel.valueOf(key.channel());
                } catch (IllegalArgumentException ex) {
                    items.add(ImportReportResponse.Item.failed(
                            key.code(), key.channel(), "Unknown channel: " + key.channel()));
                    failed++;
                    continue;
                }

                Optional<NotificationTemplate> baseOpt = templateRepository
                        .findByTemplateCodeAndChannelAndLocale(key.code(), channel, BASE_LOCALE);
                if (baseOpt.isEmpty()) {
                    items.add(ImportReportResponse.Item.skipped(
                            key.code(), key.channel(), "No base template (locale=en) found"));
                    skipped++;
                    continue;
                }
                NotificationTemplate base = baseOpt.get();

                String body = agg.body() != null ? agg.body() : "";
                String subject = agg.subject();
                if (body.isBlank()) {
                    items.add(ImportReportResponse.Item.skipped(
                            key.code(), key.channel(), "Empty body — translation pending"));
                    skipped++;
                    continue;
                }

                // L1 — 변수 일관성: target ⊂ base
                java.util.Set<String> baseVars = extractVars(base);
                java.util.Set<String> targetVars = extractVarsFromText(subject, body);
                java.util.Set<String> unknown = new java.util.HashSet<>(targetVars);
                unknown.removeAll(baseVars);
                if (!unknown.isEmpty()) {
                    items.add(ImportReportResponse.Item.failed(
                            key.code(), key.channel(),
                            "Unknown variables in translation: " + String.join(", ", unknown)));
                    failed++;
                    continue;
                }

                // 기존 target locale row 의 seq 가 있으면 update, 없으면 신규
                Optional<NotificationTemplate> targetRow = templateRepository
                        .findByTemplateCodeAndChannelAndLocale(key.code(), channel, targetLocale);
                Long existingSeq = targetRow.map(NotificationTemplate::getTemplateSeq).orElse(null);

                NotificationTemplateAdminService.DraftMutationInput input =
                        new NotificationTemplateAdminService.DraftMutationInput(
                                existingSeq,
                                key.code(),
                                channel,
                                targetLocale,
                                subject,
                                body,
                                base.getVariablesJson(),
                                base.getProviderTemplateName(),
                                base.getCategory(),
                                base.getSeverity(),
                                base.getRecipientRoles(),
                                "Imported from " + format + " (locale=" + targetLocale + ")"
                        );

                NotificationTemplateDraft draft = adminService.createDraft(input, actorUserSeq);
                items.add(ImportReportResponse.Item.created(
                        key.code(), key.channel(), draft.getDraftSeq()));
                created++;
            } catch (Exception ex) {
                log.warn("Import row failed: code={}, channel={}, err={}",
                        key.code(), key.channel(), ex.getMessage());
                items.add(ImportReportResponse.Item.failed(
                        key.code(), key.channel(), ex.getMessage()));
                failed++;
            }
        }

        return new ImportReportResponse(
                targetLocale, format, grouped.size(), created, skipped, failed, items);
    }

    // ============================================================
    // XLIFF parsing
    // ============================================================

    private List<ParsedUnit> parseXliff(InputStream stream) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE 차단 (security)
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setNamespaceAware(true);
            DocumentBuilder db = dbf.newDocumentBuilder();

            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Document doc = db.parse(new InputSource(new StringReader(content)));

            NodeList units = doc.getElementsByTagNameNS(XLIFF_NS, "trans-unit");
            // namespace 없는 XLIFF 파일도 허용 (관대 파싱)
            if (units.getLength() == 0) {
                units = doc.getElementsByTagName("trans-unit");
            }

            List<ParsedUnit> result = new ArrayList<>(units.getLength());
            for (int i = 0; i < units.getLength(); i++) {
                Element unit = (Element) units.item(i);
                String id = unit.getAttribute("id");
                String[] parts = id.split("\\|");
                if (parts.length != 3) {
                    log.warn("Skipping malformed trans-unit id: {}", id);
                    continue;
                }
                String target = textOfChild(unit, "target");
                if (target == null) continue;
                result.add(new ParsedUnit(parts[0], parts[1], parts[2], target));
            }
            return result;
        } catch (ParserConfigurationException | SAXException | IOException ex) {
            throw new LocalizationException("Failed to parse XLIFF: " + ex.getMessage(), ex);
        }
    }

    private String textOfChild(Element parent, String localName) {
        NodeList children = parent.getElementsByTagNameNS(XLIFF_NS, localName);
        if (children.getLength() == 0) children = parent.getElementsByTagName(localName);
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getParentNode() == parent) return n.getTextContent();
        }
        return null;
    }

    // ============================================================
    // CSV parsing — RFC 4180
    // ============================================================

    private List<ParsedUnit> parseCsv(InputStream stream) {
        try {
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            // BOM 제거
            if (!content.isEmpty() && content.charAt(0) == '﻿') {
                content = content.substring(1);
            }
            List<String[]> rows = parseCsvContent(content);
            if (rows.isEmpty()) return List.of();

            String[] header = rows.get(0);
            int codeIdx = indexOf(header, "template_code");
            int channelIdx = indexOf(header, "channel");
            int subjectIdx = indexOf(header, "subject");
            int bodyIdx = indexOf(header, "body_text");
            if (codeIdx < 0 || channelIdx < 0 || bodyIdx < 0) {
                throw new LocalizationException(
                        "CSV must contain template_code, channel, body_text columns");
            }

            List<ParsedUnit> result = new ArrayList<>(rows.size() - 1);
            for (int r = 1; r < rows.size(); r++) {
                String[] row = rows.get(r);
                if (row.length <= bodyIdx) continue;
                String code = safe(row, codeIdx);
                String channel = safe(row, channelIdx);
                String subject = subjectIdx >= 0 ? safe(row, subjectIdx) : null;
                String body = safe(row, bodyIdx);
                if (code.isBlank() || channel.isBlank()) continue;
                if (subject != null && !subject.isBlank()) {
                    result.add(new ParsedUnit(code, channel, FIELD_SUBJECT, subject));
                }
                result.add(new ParsedUnit(code, channel, FIELD_BODY, body));
            }
            return result;
        } catch (IOException ex) {
            throw new LocalizationException("Failed to read CSV: " + ex.getMessage(), ex);
        }
    }

    /**
     * RFC 4180 — quote 안의 줄바꿈/쉼표/이스케이프 처리.
     */
    static List<String[]> parseCsvContent(String content) {
        List<String[]> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    i++;
                } else if (c == ',') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    i++;
                } else if (c == '\r') {
                    // 다음이 \n 이면 그것까지 묶어서 처리 (CRLF)
                    if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                        i++;
                    }
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!currentRow.isEmpty()) rows.add(currentRow.toArray(new String[0]));
                    currentRow = new ArrayList<>();
                    i++;
                } else if (c == '\n') {
                    currentRow.add(field.toString());
                    field.setLength(0);
                    if (!currentRow.isEmpty()) rows.add(currentRow.toArray(new String[0]));
                    currentRow = new ArrayList<>();
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            }
        }
        // 마지막 필드/행 flush
        if (field.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(field.toString());
            rows.add(currentRow.toArray(new String[0]));
        }
        return rows;
    }

    private static int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equalsIgnoreCase(header[i].trim())) return i;
        }
        return -1;
    }

    private static String safe(String[] row, int idx) {
        return idx >= 0 && idx < row.length && row[idx] != null ? row[idx] : "";
    }

    // ============================================================
    // 변수 추출 (lint L1 과 동일 정책)
    // ============================================================

    private java.util.Set<String> extractVars(NotificationTemplate row) {
        return extractVarsFromText(row.getSubject(), row.getBodyText());
    }

    private java.util.Set<String> extractVarsFromText(String subject, String body) {
        java.util.Set<String> vars = new java.util.HashSet<>();
        addVars(vars, subject);
        addVars(vars, body);
        return vars;
    }

    private void addVars(java.util.Set<String> sink, String text) {
        if (text == null) return;
        java.util.regex.Matcher m = VAR_PATTERN.matcher(text);
        while (m.find()) sink.add(m.group(1));
    }

    // ============================================================
    // 내부 타입
    // ============================================================

    private record ParsedUnit(String code, String channel, String field, String text) {}

    private record TemplateKey(String code, String channel) {}

    /** 같은 (code, channel) 의 subject + body 를 묶어 하나의 draft 입력으로 만든다. */
    private static class AggregatedUnit {
        private String subject;
        private String body;
        void merge(ParsedUnit u) {
            if (FIELD_SUBJECT.equals(u.field())) subject = u.text();
            else if (FIELD_BODY.equals(u.field())) body = u.text();
        }
        String subject() { return subject; }
        String body() { return body; }
    }

    public static class LocalizationException extends RuntimeException {
        public LocalizationException(String msg) { super(msg); }
        public LocalizationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
