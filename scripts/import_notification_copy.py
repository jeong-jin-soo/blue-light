#!/usr/bin/env python3
"""
PR-T5 — 알림 카탈로그 markdown → SQL INSERT 변환 스크립트.

입력:  doc/Project Analysis/notification-catalog.md
출력:  stdout 에 INSERT INTO notification_catalog ... VALUES ... 문 생성.

용도:
  $ python scripts/import_notification_copy.py > /tmp/seed.sql
  $ mysql -h <host> -u <user> -p bluelight < /tmp/seed.sql

설계 원칙(CLAUDE.md §1 SSOT):
  카탈로그 메타(template_code, allowed_variables, default_category 등)는 markdown
  카탈로그(SSOT)에서 생성한다. 향후 CI drift 검증 시 본 스크립트의 결과와 DB row 를
  대조한다.

MVP 범위(P0):
  - 카탈로그의 §2~§7 표(APPLICANT/LEW/ADMIN/SYSTEM_ADMIN/SLD_MANAGER/CONCIERGE_MANAGER)
    파싱
  - 각 ID(A-01, L-01, M-01, ...)별로 1 row 생성
  - 변수 슬롯 추출은 §11 "내용 템플릿 공통 요소" 참조 — 본 스크립트는 코드별 기본
    변수만 추정(상세 변수는 카피북에서 추가 추출 — P1)

P1(후속):
  - notification-copy-templates.en.md 의 카드별 Variables 섹션에서 정확한
    allowed_variables 추출 (3000+ 라인 markdown 파싱)
  - CI drift 검증 (CI 단계에서 DB 와 markdown 의 코드 셋 일치 확인)
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


# ─────────────────────────────────────────────────────────────
# 카탈로그 마크다운 위치 — 프로젝트 루트 기준 상대 경로
# ─────────────────────────────────────────────────────────────
CATALOG_MD = Path("doc/Project Analysis/notification-catalog.md")


# ─────────────────────────────────────────────────────────────
# 역할(헤딩)별 기본 수신 역할 매핑
# ─────────────────────────────────────────────────────────────
ROLE_BY_PREFIX = {
    "A": "APPLICANT",
    "L": "LEW",
    "M": "ADMIN",
    "S": "SYSTEM_ADMIN",
    "D": "SLD_MANAGER",
    "C": "CONCIERGE_MANAGER",
}


# ─────────────────────────────────────────────────────────────
# 카탈로그 §10 카테고리 매핑 — 코드 prefix·이벤트명 키워드로 추정
# 정확한 매핑은 후속 PR (카피북에서 Category 필드 직접 추출)
# ─────────────────────────────────────────────────────────────
def infer_category(code: str, event: str) -> str:
    text = (event or "").lower()
    if any(k in text for k in ("결제", "payment", "paynow", "envoy", "invoice", "수금")):
        return "PAYMENT"
    if any(k in text for k in ("비밀번호", "비번", "로그인", "인증", "활성화", "password", "login", "session")):
        return "SECURITY"
    if any(k in text for k in ("리마인더", "reminder", "재촉", "d-", "d+")):
        return "REMINDER"
    if any(k in text for k in ("방문", "visit", "현장")):
        return "VISIT"
    if any(k in text for k in ("만료", "expir", "갱신", "renew")):
        return "EXPIRY"
    if any(k in text for k in ("마케팅", "광고", "marketing", "promo")):
        return "MARKETING"
    if any(k in text for k in ("nps", "feedback", "피드백", "설문")):
        return "FEEDBACK"
    if any(k in text for k in ("안심", "reassur")):
        return "REASSURANCE"
    if code.startswith(("M-", "S-")):
        return "OPS"
    return "STATUS"


# ─────────────────────────────────────────────────────────────
# 중요도 — Important 가 기본. ★ 패턴은 카탈로그 표의 별표(★)로 표시되지만
# 본 MVP 는 일률 IMPORTANT 로 두고 카피북 import P1 에서 정확화한다.
# ─────────────────────────────────────────────────────────────
def infer_severity(code: str, event: str) -> str:
    # 결제/보안/만료 D-1 같은 강한 신호만 CRITICAL 로 표시
    text = (event or "").lower()
    if any(k in text for k in ("d-1", "d-7", "최종", "긴급", "비밀번호 변경", "면허 발급", "expired")):
        return "CRITICAL"
    if any(k in text for k in ("nps", "안심", "다이제스트", "리마인더")):
        return "INFORMATIONAL"
    return "IMPORTANT"


# ─────────────────────────────────────────────────────────────
# MVP 변수 추정 — 카피북 import 전까지는 카탈로그의 카테고리·이벤트 이름으로
# 추정. 정확한 변수 집합은 P1 에서 카피북 카드의 Variables 필드로 교체.
# ─────────────────────────────────────────────────────────────
BASE_VARS = ["applicantName", "publicCode"]
PAYMENT_VARS = ["amount", "paynowUen", "paynowReference", "deadline"]
VISIT_VARS = ["visitAt", "managerName", "address"]
EXPIRY_VARS = ["licenceNumber", "licenceExpiryDate"]
DOC_VARS = ["documentLabel"]


def infer_variables(category: str, event: str) -> list[str]:
    vars_set: list[str] = []
    seen = set()

    def add(*items: str):
        for v in items:
            if v not in seen:
                seen.add(v)
                vars_set.append(v)

    add(*BASE_VARS)
    text = (event or "").lower()
    if category == "PAYMENT" or "결제" in text:
        add(*PAYMENT_VARS)
    if category == "VISIT" or "방문" in text:
        add(*VISIT_VARS)
    if category == "EXPIRY" or "만료" in text or "면허" in text:
        add(*EXPIRY_VARS)
    if "서류" in text or "document" in text:
        add(*DOC_VARS)
    return vars_set


# ─────────────────────────────────────────────────────────────
# 카테고리별 강제 토큰 (lint L4/L7) — MARKETING/PAYMENT 만 의미.
# ─────────────────────────────────────────────────────────────
REQUIRED_TOKENS_BY_CATEGORY = {
    "MARKETING": ["{{optOutUrl}}"],
    "PAYMENT": [],  # PayNow 변수는 L5 에서 차단 정규식으로, required 는 없음
}


def required_tokens(category: str) -> list[str]:
    return REQUIRED_TOKENS_BY_CATEGORY.get(category, [])


# ─────────────────────────────────────────────────────────────
# 마크다운 파싱
# ─────────────────────────────────────────────────────────────
@dataclass
class CatalogRow:
    code: str
    event: str  # 사람이 읽는 설명
    category: str
    severity: str
    recipient_roles: str
    allowed_variables: list[str]
    required_tokens: list[str]
    trigger_ref: str = ""  # 발송 트리거(기능/호출부) — 카피북 Trigger 필드


# 카탈로그 표 한 row 예: "| A-01 | 회원가입 → 이메일 인증 요청 | 가입 직후 | ..."
ROW_PATTERN = re.compile(
    r"^\|\s*(?P<code>[A-Z]-\d{2})\s*\|\s*(?P<event>[^|]+?)\s*\|"
)

# 카피북(본문) 위치 — 카드별 Trigger 필드를 trigger_ref 로 사용.
COPYBOOK_MD = Path("doc/Project Analysis/notification-copy-templates.en.md")
_CARD_HDR = re.compile(r"^####\s+([A-Z]-\d{2})\s+—")
_TRIGGER_ROW = re.compile(r"^\|\s*Trigger\s*\|\s*(.*?)\s*\|\s*$")


def load_triggers(copybook_md: str) -> dict[str, str]:
    """카피북에서 {code: trigger} 추출. 코드 첫 등장의 Trigger 행만 사용."""
    triggers: dict[str, str] = {}
    cur: str | None = None
    for line in copybook_md.splitlines():
        h = _CARD_HDR.match(line)
        if h:
            cur = h.group(1)
            continue
        if cur and cur not in triggers:
            t = _TRIGGER_ROW.match(line)
            if t:
                triggers[cur] = t.group(1).replace("`", "").strip()
    return triggers


def parse_catalog(md: str) -> Iterable[CatalogRow]:
    seen: set[str] = set()
    for line in md.splitlines():
        m = ROW_PATTERN.match(line)
        if not m:
            continue
        code = m.group("code")
        if code in seen:
            continue
        event = m.group("event").strip().lstrip("*").strip()
        prefix = code.split("-")[0]
        recipient = ROLE_BY_PREFIX.get(prefix, "APPLICANT")
        category = infer_category(code, event)
        severity = infer_severity(code, event)
        allowed = infer_variables(category, event)
        tokens = required_tokens(category)
        seen.add(code)
        yield CatalogRow(
            code=code,
            event=event,
            category=category,
            severity=severity,
            recipient_roles=recipient,
            allowed_variables=allowed,
            required_tokens=tokens,
        )


# ─────────────────────────────────────────────────────────────
# SQL 직렬화 — 단순한 JSON_ARRAY 표현 (mysql 8 JSON_ARRAY 도 가능하지만
# data.sql 도 같이 쓸 수 있게 그냥 TEXT 컬럼에 JSON 문자열을 박는다)
# ─────────────────────────────────────────────────────────────
def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def to_json_array(items: list[str]) -> str:
    inner = ",".join(f'"{item}"' for item in items)
    return f"[{inner}]"


def emit_row(row: CatalogRow) -> str:
    description = sql_escape((row.event or "")[:500])
    allowed_json = sql_escape(to_json_array(row.allowed_variables))
    required_json = sql_escape(to_json_array(row.required_tokens))
    trigger = sql_escape((row.trigger_ref or "")[:255])
    trigger_sql = f"'{trigger}'" if trigger else "NULL"
    return (
        "INSERT INTO notification_catalog "
        "(template_code, allowed_variables_json, default_category, default_severity, "
        "default_recipient_roles, description, required_tokens_json, trigger_ref, created_at, updated_at) "
        f"SELECT '{row.code}', '{allowed_json}', '{row.category}', '{row.severity}', "
        f"'{row.recipient_roles}', '{description}', '{required_json}', {trigger_sql}, NOW(6), NOW(6) "
        f"WHERE NOT EXISTS (SELECT 1 FROM notification_catalog WHERE template_code = '{row.code}');"
    )


# ─────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────
def main(argv: list[str]) -> int:
    project_root = Path(__file__).resolve().parent.parent
    md_path = project_root / CATALOG_MD
    if not md_path.exists():
        print(f"카탈로그 markdown 미발견: {md_path}", file=sys.stderr)
        return 1

    md = md_path.read_text(encoding="utf-8")
    rows = list(parse_catalog(md))

    # 카피북에서 trigger 백필 (있으면)
    copybook_path = project_root / COPYBOOK_MD
    triggers = load_triggers(copybook_path.read_text(encoding="utf-8")) if copybook_path.exists() else {}
    for row in rows:
        row.trigger_ref = triggers.get(row.code, "")

    print("-- PR-T5: notification_catalog seed (auto-generated)")
    print(f"-- Source: {CATALOG_MD} (+ trigger_ref from {COPYBOOK_MD})")
    print(f"-- Rows: {len(rows)} ({sum(1 for r in rows if r.trigger_ref)} with trigger)")
    print()
    for row in rows:
        print(emit_row(row))
    print()
    print(f"-- 생성 완료: {len(rows)} 행", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
