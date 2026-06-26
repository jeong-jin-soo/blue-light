"""라이선스 PDF/이미지 → 라이선스 번호·발급일·만료일 추출 (Gemini Vision).

Singapore EMA 전기 면허(Electrical Worker / Electrical Installation Licence) 문서에서
라이선스 번호와 날짜를 구조화 JSON 으로 추출한다. 백엔드(Spring)가 업로드된 LICENSE_PDF
바이트를 보내면, Gemini Vision 으로 분석해 LEW 가 검토·수정할 프리필 값을 돌려준다.

기존 패턴: app/sld/schedule_parser.py 의 _call_gemini_vision (PDF→구조화 JSON).
"""
from __future__ import annotations

import json
import logging

logger = logging.getLogger(__name__)

# Gemini system instruction — 면허 문서에서 3개 필드만 정확히 추출.
LICENSE_EXTRACTION_PROMPT = """You are a precise data extractor for Singapore electrical \
licence documents issued by EMA (Energy Market Authority) — e.g. an "Electrical Installation \
Licence" / "Licence to use or operate an electrical installation", or an Electrical Worker Licence.

From the provided licence document (PDF or image, may be multiple pages), extract exactly:
1. license_number — the licence number, e.g. shown as "Licence No. E/181761" or
   "LICENCE NO: E/ 181761". Normalise internal spaces (e.g. "E/ 181761" -> "E/181761") but
   keep the slash and any letter prefix.
2. issue_date — the start of the validity period. On the licence certificate this is the
   "valid from" date in a line like "This licence is valid from <issue> to <expiry>".
   If no validity line exists, use the letter/issue date printed on the document.
3. expiry_date — the end of the validity period (the "to" date in the validity line / when
   the licence expires).

Rules:
- Return STRICT JSON with keys: "license_number", "issue_date", "expiry_date".
- Dates MUST be ISO format "YYYY-MM-DD". Convert any other format (e.g. "17/06/2026",
  "16 Jun 2027", "31-12-2025") to ISO. If a date has a time component (e.g.
  "17/06/2026 15:42"), keep only the date.
- If a field is NOT clearly present, set it to null. Do NOT guess.
- Output ONLY the JSON object, nothing else.
"""


async def parse_license(
    file_bytes: bytes,
    mime_type: str,
    api_key: str | None = None,
) -> dict:
    """라이선스 문서 바이트에서 번호·발급일·만료일을 추출한다.

    Returns:
        {"license_number": str|None, "issue_date": str|None, "expiry_date": str|None}
        (Gemini 가 채우지 못한 필드는 None.)
    """
    from google import genai
    from google.genai import types

    from app.config import settings

    resolved_key = api_key or settings.gemini_api_key
    if not resolved_key:
        raise ValueError("GEMINI_API_KEY is not configured")

    client = genai.Client(api_key=resolved_key)

    logger.info("Parsing licence document (%s, %d bytes)", mime_type, len(file_bytes))

    response = await client.aio.models.generate_content(
        # 라이선스 필드 추출은 단순 구조화 작업 — flash 로 충분(샘플 검증 완료, Pro 는 무료할당량 빡빡).
        model=settings.gemini_model,
        contents=[
            types.Content(
                parts=[
                    types.Part.from_text(
                        text="Extract the licence number, issue date and expiry date from this licence document."
                    ),
                    types.Part.from_bytes(data=file_bytes, mime_type=mime_type),
                ],
            ),
        ],
        config=types.GenerateContentConfig(
            system_instruction=LICENSE_EXTRACTION_PROMPT,
            response_mime_type="application/json",
            temperature=0.0,
        ),
    )

    raw = (response.text or "").strip()
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        logger.warning("Licence parse: non-JSON response: %s", raw[:200])
        raise ValueError("Gemini returned non-JSON response") from exc

    # 키 정규화 — 누락 필드는 None.
    return {
        "license_number": _clean_str(data.get("license_number")),
        "issue_date": _clean_str(data.get("issue_date")),
        "expiry_date": _clean_str(data.get("expiry_date")),
    }


def _clean_str(value) -> str | None:
    if value is None:
        return None
    s = str(value).strip()
    if not s or s.lower() in {"null", "none", "n/a", "-"}:
        return None
    return s
