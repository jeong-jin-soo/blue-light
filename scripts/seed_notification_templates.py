#!/usr/bin/env python3
"""
PR-W0 — 알림 카피북(notification-copy-templates.en.md) → notification_templates SQL 변환.

`import_notification_copy.py` 가 카탈로그 *메타*(notification_catalog)만 생성하는 것과 달리,
본 스크립트는 실제 발송 *본문*(notification_templates: subject/body_text)을 생성한다.

입력:  doc/Project Analysis/notification-copy-templates.en.md
출력:  stdout 에 INSERT INTO notification_templates ... ON DUPLICATE KEY UPDATE 문.

용도:
  $ python3 scripts/seed_notification_templates.py --codes A-10,A-15,A-17,A-22 > /tmp/templates.sql
  $ python3 scripts/seed_notification_templates.py --all > /tmp/templates.sql

설계 메모(중요 — 실제 런타임 제약과 일치시킬 것):
  1) 렌더러(TemplateRenderer)는 단순 {{var}} 치환만 한다. footer/system 변수 자동 주입이 없으므로
     본문은 **자기완결형 HTML** 이어야 한다(헤더+본문+인라인 푸터).
  2) 변수명 = payload 키. 호출부가 도메인 데이터를 카피북 변수 슬롯에 매핑한다
     (예: Application 은 publicCode 가 없으므로 호출부가 applicationSeq 를 "publicCode" 키로 전달).
  3) EmailChannelAdapter 는 sendGenericEmail(to, subject, body) 만 호출 — 첨부 미지원.
     MVP 는 EMAIL + IN_APP 만 시드(SMS/WhatsApp 제외, 결정 #6).
  4) 카나리 A-20 은 라이브 증명용으로 data.sql 에 수기 정교화되어 있어 본 스크립트 대상에서 제외.

unique key 는 (template_code, channel, locale) 가정 → ON DUPLICATE KEY UPDATE 멱등.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

COPYBOOK = Path("doc/Project Analysis/notification-copy-templates.en.md")

# 카나리(수기 관리) — 스크립트 생성 제외
EXCLUDE_CODES = {"A-20"}


# ─────────────────────────────────────────────────────────────
# 데이터 모델
# ─────────────────────────────────────────────────────────────
@dataclass
class Card:
    code: str
    title: str
    category: str = ""
    severity: str = ""
    recipient: str = ""
    channels: str = ""
    variables: list[str] = field(default_factory=list)
    email_subject: str = ""
    email_headline: str = ""
    email_body_lines: list[str] = field(default_factory=list)  # blockquote 원문(접두 '> ' 제거)
    email_cta_label: str = ""
    email_cta_var: str = ""       # 예: {{ctaUrl}}
    email_footer_reason: str = ""
    inapp_title: str = ""
    inapp_body: str = ""

    def has_email(self) -> bool:
        return bool(self.email_subject and self.email_body_lines)

    def has_inapp(self) -> bool:
        return bool(self.inapp_title and self.inapp_body)


# ─────────────────────────────────────────────────────────────
# 파싱
# ─────────────────────────────────────────────────────────────
CARD_HDR = re.compile(r"^####\s+([A-Z]-\d{2})\s+—\s+(.*)$")
META_ROW = re.compile(r"^\|\s*([A-Za-z ]+?)\s*\|\s*(.*?)\s*\|\s*$")
BULLET = re.compile(r"^\s*-\s+\*\*(?P<key>[^*]+)\*\*\s*:\s*(?P<val>.*)$")
SECTION = re.compile(r"^\*\*(Email|In-app|SMS|WhatsApp)\*\*", re.IGNORECASE)
BACKTICK = re.compile(r"`([^`]*)`")
ROLE_BY_PREFIX = {"A": "APPLICANT", "L": "LEW", "M": "ADMIN", "S": "SYSTEM_ADMIN",
                  "D": "SLD_MANAGER", "C": "CONCIERGE_MANAGER"}


def _first_backtick(text: str) -> str:
    m = BACKTICK.search(text)
    return m.group(1) if m else text.strip()


def _norm_severity(raw: str) -> str:
    t = raw.lower()
    if "critical" in t:
        return "CRITICAL"
    if "important" in t:
        return "IMPORTANT"
    if "marketing" in t:
        return "MARKETING"
    return "INFORMATIONAL"


def parse_cards(md: str) -> list[Card]:
    cards: list[Card] = []
    cur: Card | None = None
    section = None  # None | 'email' | 'inapp' | 'skip'
    in_email_body = False

    for raw in md.splitlines():
        hdr = CARD_HDR.match(raw)
        if hdr:
            if cur:
                cards.append(cur)
            cur = Card(code=hdr.group(1), title=hdr.group(2).strip())
            section = None
            in_email_body = False
            continue
        if cur is None:
            continue

        sec = SECTION.match(raw.strip())
        if sec:
            name = sec.group(1).lower()
            section = {"email": "email", "in-app": "inapp"}.get(name, "skip")
            in_email_body = False
            continue

        # 메타 테이블 (섹션 진입 전)
        if section is None:
            mr = META_ROW.match(raw)
            if mr:
                key, val = mr.group(1).strip().lower(), mr.group(2).strip()
                if key == "category":
                    cur.category = val.upper()
                elif key == "severity":
                    cur.severity = _norm_severity(val)
                elif key == "recipient":
                    cur.recipient = val
                elif key == "channels":
                    cur.channels = val
                elif key == "variables":
                    cur.variables = [m.group(1).strip("{} ")
                                     for m in re.finditer(r"\{\{([^}]+)\}\}", val)]
            continue

        if section == "email":
            b = BULLET.match(raw)
            if b:
                key, val = b.group("key").strip().lower(), b.group("val").strip()
                in_email_body = False
                if key == "subject":
                    cur.email_subject = _first_backtick(val)
                elif key == "headline":
                    cur.email_headline = _first_backtick(val)
                elif key == "body":
                    in_email_body = True
                elif key in ("primary cta", "cta"):
                    # `label` → `{{var}}` (`/path`)
                    ticks = BACKTICK.findall(val)
                    if ticks:
                        cur.email_cta_label = ticks[0]
                    vm = re.search(r"\{\{[^}]+\}\}", val)
                    if vm:
                        cur.email_cta_var = vm.group(0)
                elif key == "footer reason":
                    cur.email_footer_reason = _first_backtick(val)
                continue
            if in_email_body:
                if raw.lstrip().startswith(">"):
                    cur.email_body_lines.append(re.sub(r"^\s*>\s?", "", raw))
                elif raw.strip() == "":
                    if cur.email_body_lines and cur.email_body_lines[-1] != "":
                        cur.email_body_lines.append("")
                else:
                    in_email_body = False
            continue

        if section == "inapp":
            b = BULLET.match(raw)
            if b:
                key, val = b.group("key").strip().lower(), b.group("val").strip()
                if key == "title":
                    cur.inapp_title = _first_backtick(val)
                elif key == "body":
                    cur.inapp_body = _first_backtick(val)
            continue

    if cur:
        cards.append(cur)
    return cards


# ─────────────────────────────────────────────────────────────
# HTML 조립 (자기완결형 — 렌더러가 footer 주입 안 함)
# ─────────────────────────────────────────────────────────────
def _md_inline(text: str) -> str:
    text = (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    # **bold** → <strong> (이스케이프 이후이므로 ** 는 그대로 남아있음)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    return text


def _body_html(lines: list[str]) -> str:
    """blockquote 원문 라인 → <p>/<blockquote> HTML. 빈 줄 = 단락 분리, '> ' = 중첩 인용."""
    html: list[str] = []
    para: list[str] = []
    quote: list[str] = []

    def flush_para():
        if para:
            html.append(f'<p style="margin:0 0 16px">{"<br>".join(para)}</p>')
            para.clear()

    def flush_quote():
        if quote:
            html.append('<blockquote style="margin:0 0 16px;padding:8px 16px;'
                        'border-left:3px solid #ccc;color:#555">'
                        + "<br>".join(quote) + "</blockquote>")
            quote.clear()

    for ln in lines:
        if ln.strip() == "":
            flush_para()
            flush_quote()
            continue
        if ln.lstrip().startswith(">"):  # 중첩 인용 (예: revisionNotes)
            flush_para()
            quote.append(_md_inline(re.sub(r"^\s*>\s?", "", ln)))
        else:
            flush_quote()
            para.append(_md_inline(ln))
    flush_para()
    flush_quote()
    return "".join(html)


HEADER_HTML = (
    '<div style="border-bottom:1px solid #E5E7EB;padding:16px 0;margin-bottom:24px">'
    '<span style="font-size:18px;font-weight:700;color:#0F766E">LicenseKaki</span><br>'
    '<span style="font-size:12px;color:#888">Singapore Electrical Installation Licence Platform</span>'
    "</div>"
)


def _footer_html(card: Card) -> str:
    reason = card.email_footer_reason or "you have an update on your LicenseKaki account."
    anti_phishing = ""
    if card.category in ("SECURITY", "PAYMENT"):
        anti_phishing = (
            '<p style="margin:8px 0 0;font-size:12px;color:#888">'
            "Anti-phishing: our only sender domain is @licensekaki.sg. We never ask for your "
            "password, OTP, or PayNow PIN by email. Verify any link before clicking.</p>"
        )
    return (
        '<hr style="border:none;border-top:1px solid #ddd;margin:24px 0">'
        f'<p style="margin:0;font-size:12px;color:#888">This is a transactional email from '
        f"LicenseKaki. You are receiving it because {reason}</p>"
        f"{anti_phishing}"
        '<p style="margin:8px 0 0;font-size:12px;color:#888">LicenseKaki Pte Ltd · '
        "PDPA enquiries: dpo@licensekaki.sg</p>"
    )


def build_email_body(card: Card) -> str:
    parts = ['<div style="font-family:Helvetica,Arial,sans-serif;color:#222;line-height:1.5;'
             'max-width:600px;margin:0 auto;padding:0 16px">', HEADER_HTML]
    if card.email_headline:
        parts.append(f'<h1 style="font-size:20px;margin:0 0 16px">{_md_inline(card.email_headline)}</h1>')
    parts.append(_body_html(card.email_body_lines))
    if card.email_cta_label and card.email_cta_var:
        parts.append(
            f'<p style="margin:24px 0"><a href="{card.email_cta_var}" '
            'style="display:inline-block;background:#0F766E;color:#fff;text-decoration:none;'
            f'padding:10px 20px;border-radius:6px;font-weight:600">{_md_inline(card.email_cta_label)}</a></p>'
        )
    parts.append(_footer_html(card))
    parts.append("</div>")
    return "".join(parts)


# ─────────────────────────────────────────────────────────────
# SQL 직렬화
# ─────────────────────────────────────────────────────────────
def sql_str(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def json_array(items: list[str]) -> str:
    return "[" + ",".join(f'"{i}"' for i in items) + "]"


def emit_rows(card: Card) -> list[str]:
    rows: list[str] = []
    recipient_roles = ROLE_BY_PREFIX.get(card.code.split("-")[0], "APPLICANT")
    vars_json = json_array(card.variables)
    common = (card.code, card.category or "STATUS", card.severity or "IMPORTANT",
              recipient_roles, vars_json)

    def value_tuple(channel: str, subject: str | None, body: str) -> str:
        code, cat, sev, roles, vj = common
        return (
            f"({sql_str(code)}, {sql_str(channel)}, 'en', NULL, {sql_str(subject)}, "
            f"{sql_str(body)}, {sql_str(vj)}, {sql_str(code)}, {sql_str(cat)}, {sql_str(sev)}, "
            f"{sql_str(roles)}, TRUE, NOW(), NOW())"
        )

    if card.has_email():
        rows.append(value_tuple("EMAIL", card.email_subject, build_email_body(card)))
    if card.has_inapp():
        rows.append(value_tuple("IN_APP", card.inapp_title, _md_inline(card.inapp_body)))
    return rows


INSERT_HEAD = (
    "INSERT INTO notification_templates\n"
    "    (template_code, channel, locale, provider_template_name, subject, body_text,\n"
    "     variables_json, catalog_meta_key, category, severity, recipient_roles, enabled,\n"
    "     created_at, updated_at)\nVALUES\n"
)
ON_DUP = (
    "\nON DUPLICATE KEY UPDATE\n"
    "    subject = VALUES(subject), body_text = VALUES(body_text),\n"
    "    variables_json = VALUES(variables_json), catalog_meta_key = VALUES(catalog_meta_key),\n"
    "    category = VALUES(category), severity = VALUES(severity),\n"
    "    recipient_roles = VALUES(recipient_roles), enabled = VALUES(enabled),\n"
    "    deleted_at = NULL, updated_at = NOW();\n"
)


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--codes", help="콤마구분 코드 목록 (예: A-10,A-15)")
    ap.add_argument("--all", action="store_true", help="EXCLUDE 제외 전 카드")
    args = ap.parse_args(argv[1:])

    root = Path(__file__).resolve().parent.parent
    md_path = root / COPYBOOK
    if not md_path.exists():
        print(f"카피북 미발견: {md_path}", file=sys.stderr)
        return 1

    cards = parse_cards(md_path.read_text(encoding="utf-8"))
    by_code: dict[str, Card] = {}
    for c in cards:
        by_code.setdefault(c.code, c)  # 첫 등장 우선(다이제스트 2차 등장 무시)

    if args.codes:
        want = [c.strip() for c in args.codes.split(",") if c.strip()]
    elif args.all:
        want = [c for c in by_code if c not in EXCLUDE_CODES]
    else:
        print("--codes 또는 --all 필요", file=sys.stderr)
        return 2

    all_rows: list[str] = []
    skipped: list[str] = []
    for code in want:
        if code in EXCLUDE_CODES:
            skipped.append(f"{code}(excluded)")
            continue
        card = by_code.get(code)
        if card is None:
            skipped.append(f"{code}(not found)")
            continue
        rows = emit_rows(card)
        if not rows:
            skipped.append(f"{code}(no email/inapp)")
            continue
        all_rows.extend(rows)

    print("-- PR-W0: notification_templates seed (auto-generated)")
    print(f"-- Source: {COPYBOOK}")
    print(f"-- Codes: {', '.join(want)}")
    print(f"-- Rows: {len(all_rows)}")
    print()
    if all_rows:
        print(INSERT_HEAD + ",\n".join("    " + r for r in all_rows) + ON_DUP)
    if skipped:
        print(f"-- skipped: {', '.join(skipped)}", file=sys.stderr)
    print(f"-- 생성: {len(all_rows)} rows", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
