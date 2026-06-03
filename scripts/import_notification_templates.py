#!/usr/bin/env python3
"""
알림 카피북 markdown → notification_templates INSERT SQL 변환 스크립트.

입력:  doc/Project Analysis/notification-copy-templates.en.md  (카드 102개, §2~§7)
출력:  stdout 에 INSERT INTO notification_templates ... ON DUPLICATE KEY UPDATE 문.

용도:
  $ python3 scripts/import_notification_templates.py > /tmp/templates_seed.sql
  $ mysql -h <host> -u <user> -p bluelight < /tmp/templates_seed.sql

규칙:
  - 카드 1개(A-01 등) → 채널별(EMAIL/IN_APP/SMS) 1 row.  WhatsApp 은 NotificationChannel
    enum 에 없으므로 제외.
  - template_code = catalog_meta_key = 카드 코드(A-01).  locale = 'en'.
  - category/severity/recipient 는 NotificationCategory/Severity enum 유효값으로 매핑.
  - body_text: EMAIL = Headline+Body+CTA 를 단순 HTML 로 합성, IN_APP/SMS = 본문 텍스트.

설계 원칙(CLAUDE.md §1 SSOT): 카피 본문의 정본은 markdown. 본 스크립트로 DB 시드를 생성한다.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

COPYBOOK = Path("doc/Project Analysis/notification-copy-templates.en.md")

# ── enum 매핑 ──────────────────────────────────────────────
VALID_CATEGORY = {"SECURITY", "STATUS", "PAYMENT", "REMINDER", "VISIT",
                  "REASSURANCE", "EXPIRY", "MARKETING", "FEEDBACK"}
# 카피북 Category → 유효 NotificationCategory
CATEGORY_MAP = {"OPS": "STATUS"}  # OPS 는 enum 에 없음 → STATUS


def map_category(raw: str) -> str:
    val = (raw or "").strip().upper()
    val = CATEGORY_MAP.get(val, val)
    return val if val in VALID_CATEGORY else "STATUS"


def map_severity(raw: str) -> str:
    t = (raw or "").lower()
    if "critical" in t:
        return "CRITICAL"
    if "informational" in t or "marketing" in t:
        return "INFORMATIONAL"
    return "IMPORTANT"


def map_recipient(raw: str) -> str:
    t = (raw or "")
    roles = []
    if re.search(r"system admin", t, re.I):
        roles.append("SYSTEM_ADMIN")
    if re.search(r"concierge manager", t, re.I):
        roles.append("CONCIERGE_MANAGER")
    if re.search(r"sld manager", t, re.I):
        roles.append("SLD_MANAGER")
    if re.search(r"\blew\b", t, re.I):
        roles.append("LEW")
    if re.search(r"applicant", t, re.I):
        roles.append("APPLICANT")
    # "Admin" (System Admin 이 아닌 단독 Admin / Admin CC)
    if re.search(r"(?<!system )admin", t, re.I) and "SYSTEM_ADMIN" not in roles:
        roles.append("ADMIN")
    elif re.search(r"admin cc", t, re.I):
        roles.append("ADMIN")
    # 중복 제거, 순서 유지
    seen, out = set(), []
    for r in roles:
        if r not in seen:
            seen.add(r)
            out.append(r)
    return ",".join(out) or "APPLICANT"


# ── markdown 유틸 ─────────────────────────────────────────
def strip_inline(s: str) -> str:
    """backtick / 끝 공백 제거."""
    s = s.strip()
    if s.startswith("`") and s.endswith("`") and len(s) >= 2:
        s = s[1:-1]
    return s.strip()


def md_bold_to_html(s: str) -> str:
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)


def html_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def sql_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


# ── 카드 분해 ─────────────────────────────────────────────
CARD_HEADER = re.compile(r"^####\s+([A-Z]-\d+)\s+[—-]\s+(.+?)\s*$")
META_ROW = re.compile(r"^\|\s*([A-Za-z /]+?)\s*\|\s*(.+?)\s*\|\s*$")
FIELD = re.compile(r"^-\s+\*\*(.+?)\*\*\s*:\s*(.*)$")
# 채널 섹션 헤더: **Email**, **In-app**, **SMS** (단, 'N/A' 결합형은 본문 없음)
SECTION = re.compile(r"^\*\*(Email|In-app|SMS|WhatsApp)\*\*(.*)$")


def split_cards(text: str):
    lines = text.splitlines()
    cards = []
    cur = None
    for line in lines:
        m = CARD_HEADER.match(line)
        if m:
            if cur:
                cards.append(cur)
            cur = {"code": m.group(1), "title": m.group(2), "lines": []}
        elif cur is not None:
            # 다음 ## / ### 섹션 헤더를 만나면 카드 종료
            if re.match(r"^#{1,3}\s", line):
                cards.append(cur)
                cur = None
            else:
                cur["lines"].append(line)
    if cur:
        cards.append(cur)
    return cards


def parse_meta(lines):
    meta = {}
    for line in lines:
        m = META_ROW.match(line)
        if m:
            key = m.group(1).strip().lower()
            meta[key] = m.group(2).strip()
    return meta


def extract_variables(meta_variables: str):
    return re.findall(r"\{\{([a-zA-Z0-9_]+)\}\}", meta_variables or "")


def section_blocks(lines):
    """카드 본문을 채널 섹션별 (name, header_rest, body_lines) 로 분해."""
    blocks = []
    cur = None
    for line in lines:
        m = SECTION.match(line)
        if m:
            if cur:
                blocks.append(cur)
            cur = {"name": m.group(1), "rest": m.group(2), "lines": []}
        elif line.startswith("**") and cur and re.match(r"^\*\*(Edge cases|Variants|Notes|Reviewer)", line):
            blocks.append(cur)
            cur = None
        elif cur is not None:
            cur["lines"].append(line)
    if cur:
        blocks.append(cur)
    return blocks


def parse_fields(body_lines):
    """- **Field**: value  (Body 는 blockquote 멀티라인) 형태 파싱."""
    fields = {}
    i = 0
    n = len(body_lines)
    while i < n:
        line = body_lines[i]
        m = FIELD.match(line)
        if m:
            name = m.group(1).strip().lower()
            val = m.group(2).strip()
            # Body 등은 다음 줄들이 blockquote(>)로 이어질 수 있음
            quote = []
            j = i + 1
            while j < n:
                nxt = body_lines[j]
                if nxt.strip().startswith(">"):
                    quote.append(re.sub(r"^\s*>\s?", "", nxt))
                    j += 1
                elif nxt.strip() == "" and quote:
                    # 인용 내부 빈 줄 — 다음이 인용이면 문단 구분
                    if j + 1 < n and body_lines[j + 1].strip().startswith(">"):
                        quote.append("")
                        j += 1
                    else:
                        break
                else:
                    break
            if quote:
                val = (val + "\n" + "\n".join(quote)).strip()
            fields[name] = val
            i = j
        else:
            i += 1
    return fields


def blockquote_to_html(text: str) -> str:
    """blockquote 본문(문단/리스트) → <p>/<ul> HTML."""
    paras = re.split(r"\n\s*\n", text.strip())
    html = []
    for p in paras:
        p = p.strip()
        if not p:
            continue
        lines = [l.strip() for l in p.splitlines() if l.strip()]
        if lines and all(l.startswith("- ") for l in lines):
            items = "".join(f"<li>{md_bold_to_html(l[2:].strip())}</li>" for l in lines)
            html.append(f"<ul>{items}</ul>")
        else:
            joined = " ".join(lines)
            html.append(f"<p>{md_bold_to_html(joined)}</p>")
    return "".join(html)


EMAIL_WRAP_OPEN = ('<!DOCTYPE html><html><body style="font-family:Helvetica,Arial,'
                   'sans-serif;color:#222;line-height:1.5">')
EMAIL_FOOTER = ('<hr style="border:none;border-top:1px solid #ddd;margin:24px 0">'
                '<p style="font-size:12px;color:#888">This is an automated message '
                'from LicenseKaki. Please do not reply.</p></body></html>')


def compose_email_body(fields) -> str:
    parts = [EMAIL_WRAP_OPEN]
    headline = fields.get("headline")
    if headline:
        parts.append(f"<h2 style=\"font-size:18px\">{md_bold_to_html(strip_inline(headline))}</h2>")
    body = fields.get("body")
    if body:
        parts.append(blockquote_to_html(body))
    cta = fields.get("primary cta")
    if cta:
        # 형식: `Label` → `url`
        m = re.search(r"`([^`]+)`\s*(?:→|->)\s*`?([^`\s]+)`?", cta)
        if m:
            label, url = m.group(1), m.group(2)
            parts.append(f'<p style="margin:24px 0"><a href="{url}" '
                         f'style="background:#0d9488;color:#fff;padding:10px 18px;'
                         f'border-radius:6px;text-decoration:none;display:inline-block">'
                         f'{label}</a></p>')
    parts.append(EMAIL_FOOTER)
    return "".join(parts)


def emit_row(code, channel, subject, body, variables, category, severity, recipient):
    subj = "NULL" if subject is None else f"'{sql_escape(subject)}'"
    vars_json = json.dumps(variables, ensure_ascii=False)
    return (
        "INSERT INTO notification_templates "
        "(template_code, channel, locale, provider_template_name, subject, body_text, "
        "variables_json, enabled, version, catalog_meta_key, category, severity, "
        "recipient_roles, created_at, updated_at) VALUES ("
        f"'{code}', '{channel}', 'en', NULL, {subj}, '{sql_escape(body)}', "
        f"'{sql_escape(vars_json)}', TRUE, 0, '{code}', '{category}', '{severity}', "
        f"'{recipient}', NOW(6), NOW(6)) "
        "ON DUPLICATE KEY UPDATE subject=VALUES(subject), body_text=VALUES(body_text), "
        "variables_json=VALUES(variables_json), category=VALUES(category), "
        "severity=VALUES(severity), recipient_roles=VALUES(recipient_roles), "
        "catalog_meta_key=VALUES(catalog_meta_key), updated_at=NOW(6);"
    )


def main(argv):
    root = Path(__file__).resolve().parent.parent
    path = root / COPYBOOK
    if not path.exists():
        print(f"카피북 미발견: {path}", file=sys.stderr)
        return 1
    text = path.read_text(encoding="utf-8")
    cards = split_cards(text)

    rows = []
    stats = {"cards": 0, "EMAIL": 0, "IN_APP": 0, "SMS": 0, "skipped_wa": 0}
    for card in cards:
        code = card["code"]
        meta = parse_meta(card["lines"])
        category = map_category(meta.get("category", ""))
        severity = map_severity(meta.get("severity", ""))
        recipient = map_recipient(meta.get("recipient", ""))
        variables = extract_variables(meta.get("variables", ""))
        stats["cards"] += 1

        for blk in section_blocks(card["lines"]):
            name = blk["name"]
            rest = (blk.get("rest") or "")
            # 'N/A' / 'not sent' 섹션 스킵
            joined = (rest + " " + " ".join(blk["lines"])).lower()
            if name == "WhatsApp":
                stats["skipped_wa"] += 1
                continue
            fields = parse_fields(blk["lines"])
            if name == "Email":
                subject = strip_inline(fields.get("subject", "")) if fields.get("subject") else None
                if not subject and ("n/a" in rest.lower()):
                    continue
                if not fields.get("body") and not fields.get("headline") and not subject:
                    continue
                body = compose_email_body(fields)
                rows.append(emit_row(code, "EMAIL", subject, body, variables, category, severity, recipient))
                stats["EMAIL"] += 1
            elif name == "In-app":
                title = strip_inline(fields.get("title", "")) if fields.get("title") else None
                body_raw = fields.get("body")
                if not body_raw:
                    continue
                body = strip_inline(body_raw)
                rows.append(emit_row(code, "IN_APP", title, body, variables, category, severity, recipient))
                stats["IN_APP"] += 1
            elif name == "SMS":
                body_raw = fields.get("body")
                if not body_raw or "n/a" in joined[:40]:
                    continue
                body = strip_inline(body_raw)
                rows.append(emit_row(code, "SMS", None, body, variables, category, severity, recipient))
                stats["SMS"] += 1

    print("-- notification_templates seed (auto-generated from copybook)")
    print(f"-- Source: {COPYBOOK}")
    print(f"-- Cards: {stats['cards']}  EMAIL: {stats['EMAIL']}  IN_APP: {stats['IN_APP']}  "
          f"SMS: {stats['SMS']}  (WhatsApp sections skipped: {stats['skipped_wa']})")
    print()
    for r in rows:
        print(r)
    print(f"\n-- 생성 완료: {len(rows)} rows", file=sys.stderr)
    print(f"-- stats: {stats}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
