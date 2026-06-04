#!/usr/bin/env python3
"""
notification_templates 본문/제목의 mojibake(이중 인코딩) 치환 SQL 생성.

배경: 시드 적용 시 mysql client 가 latin1(=Windows-1252) connection 으로 동작해
UTF-8 바이트가 이중 인코딩됨. 예: '—'(U+2014, E2 80 94) → 'â€”'(C3A2 E282AC E2809D).

본 스크립트는 카피북의 비ASCII 문자별 mojibake(= char.encode('utf-8').decode('cp1252'))를
계산해 body_text/subject 에 REPLACE 하는 UPDATE 문을 출력한다.

★ 반드시 mysql --default-character-set=utf8mb4 로 적용할 것. (안 그러면 치환문 자체가 또 깨짐)
"""
from __future__ import annotations
import sys
from pathlib import Path

COPYBOOK = Path("doc/Project Analysis/notification-copy-templates.en.md")


def sql_lit(s: str) -> str:
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    text = (root / COPYBOOK).read_text(encoding="utf-8")
    # 카피북에 등장하는 비ASCII 문자 → mojibake 매핑 (cp1252 로 디코드 가능한 것만)
    seen = {}
    for ch in text:
        if ord(ch) <= 127 or ch in seen:
            continue
        try:
            moji = ch.encode("utf-8").decode("windows-1252")
        except Exception:
            continue
        # moji 가 ASCII 와 충돌하지 않고, 자기 자신과 다른 경우만
        if moji and moji != ch:
            seen[ch] = moji

    # 더 긴 mojibake 부터 치환(부분 겹침 방지). 길이 desc 정렬.
    pairs = sorted(seen.items(), key=lambda kv: -len(kv[1]))

    def nested(col: str) -> str:
        expr = col
        for ch, moji in pairs:
            expr = f"REPLACE({expr}, {sql_lit(moji)}, {sql_lit(ch)})"
        return expr

    where = " OR ".join(
        f"{col} LIKE {sql_lit('%' + moji + '%')}"
        for col in ("body_text", "subject")
        for ch, moji in pairs
    )

    print("-- notification_templates mojibake 치환 (utf8mb4 connection 필수)")
    print(f"-- pairs: {len(pairs)}")
    print("UPDATE notification_templates SET")
    print(f"  body_text = {nested('body_text')},")
    print(f"  subject   = {nested('subject')}")
    print(f"WHERE {where};")
    print(f"-- 생성 pairs: {[(c, m) for c, m in pairs]}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
