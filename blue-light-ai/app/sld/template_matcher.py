"""
SLD 템플릿 매칭 — 다차원 스코어링 + PDF→이미지 변환 + 정규화.

사용자 스펙을 받아 DB에서 가장 유사한 실무 SLD 템플릿을 찾고,
매칭된 템플릿을 generator.py 입력 형식으로 정규화한다.

핵심 전략: 매칭된 템플릿을 BASE로 사용하고, 사용자 스펙에 맞게 delta만 적용.

Usage:
    from app.sld.template_matcher import find_similar_templates, normalize_template_to_requirements

    spec = {"supply_type": "three_phase", "kva": 45.0, "circuit_count": 8}
    templates = find_similar_templates(spec, limit=3)
    if templates:
        base_requirements = normalize_template_to_requirements(templates[0]["detail"])
"""

import base64
import json
import logging
import os
from pathlib import Path

from app.config import settings
from app.db.connection import get_db

logger = logging.getLogger(__name__)

# 템플릿 PDF 루트 디렉토리
TEMPLATES_BASE_DIR = Path(__file__).resolve().parent.parent.parent / "data" / "sld-info"

# 스코어링 가중치
_WEIGHTS = {
    "kva": 0.25,
    "circuit_count": 0.30,
    "breaker_type": 0.20,
    "metering": 0.15,
    "elcb_type": 0.10,
}

# fallback 임계값 — 최고 점수가 이 값 미만이면 빈 리스트
_MIN_SIMILARITY_SCORE = 0.3


# ── 다차원 스코어링 매칭 ───────────────────────────────

def find_similar_templates(spec: dict, limit: int = 3) -> list[dict]:
    """
    다차원 스코어링으로 가장 유사한 SLD 템플릿을 DB에서 찾는다.

    스코어링:
        - phase: 불일치 시 제외 (필수 필터)
        - kva: kVA 차이 비율 (가중치 0.25)
        - circuit_count: 서킷 수 차이 (가중치 0.30) — 레이아웃 유사도 핵심
        - breaker_type: MCB/MCCB/ACB 일치 (가중치 0.20)
        - metering: SP/CT meter 일치 (가중치 0.15)
        - elcb_type: RCCB/ELCB 일치 (가중치 0.10)

    Args:
        spec: 사용자 스펙. 필수: supply_type, kva. 선택: circuit_count 등.
        limit: 반환할 최대 건수.

    Returns:
        유사도 순 정렬된 템플릿 리스트. 최고 점수 0.3 미만이면 빈 리스트.
    """
    supply_type = spec.get("supply_type", "").strip()
    kva = spec.get("kva")

    if not supply_type or kva is None:
        logger.warning("find_similar_templates: supply_type 또는 kva 누락")
        return []

    try:
        kva = float(kva)
    except (ValueError, TypeError):
        logger.error(f"find_similar_templates: kva 변환 실패: {kva}")
        return []

    # 선택적 매칭 차원
    circuit_count = spec.get("circuit_count", 0) or 0
    main_breaker_type = (spec.get("main_breaker_type", "") or "").upper()
    metering_type = (spec.get("metering_type", "") or "").lower()
    elcb_type = (spec.get("elcb_type", "") or "").upper()

    # DB에서 같은 phase의 모든 템플릿 조회
    query = """
        SELECT sld_template_seq, phase, kva, main_breaker_type,
               circuit_count, filename, file_path, detail_json
        FROM sld_templates
        WHERE phase = %s
          AND kva IS NOT NULL
    """

    try:
        with get_db() as conn:
            with conn.cursor() as cursor:
                cursor.execute(query, (supply_type,))
                rows = cursor.fetchall()
    except Exception as e:
        logger.error(f"find_similar_templates DB 오류: {e}")
        return []

    if not rows:
        logger.warning(f"find_similar_templates: phase={supply_type} 템플릿 없음")
        return []

    # kVA 범위 (정규화용)
    max_kva = max(float(r["kva"]) for r in rows)

    # 스코어링
    scored = []
    for row in rows:
        row_kva = float(row["kva"])
        row_breaker = (row["main_breaker_type"] or "").upper()
        row_circuit_count = row["circuit_count"] or 0

        # detail_json에서 추가 정보 추출
        detail = row.get("detail_json")
        if isinstance(detail, str):
            detail = json.loads(detail)

        row_metering = ""
        row_elcb = ""
        if isinstance(detail, dict):
            metering_info = detail.get("metering", {})
            if isinstance(metering_info, dict):
                row_metering = (metering_info.get("type", "") or "").lower()
            elcb_info = detail.get("elcb", {})
            if isinstance(elcb_info, dict):
                row_elcb = (elcb_info.get("type", "") or "").upper()

        # 개별 점수 계산
        kva_diff_ratio = abs(row_kva - kva) / max_kva if max_kva > 0 else 0
        kva_score = max(0, 1.0 - kva_diff_ratio)

        # sub_circuits가 없는 템플릿(circuit_count=0)은 cable 상속 불가 → 페널티
        if row_circuit_count == 0:
            circuit_score = 0.1  # 서킷 데이터 없으면 큰 페널티
        elif circuit_count > 0:
            count_diff = abs(row_circuit_count - circuit_count)
            circuit_score = max(0, 1.0 - count_diff / 10.0)
        else:
            circuit_score = 0.5  # 사용자가 circuit_count 미지정이면 중립

        breaker_score = 1.0 if (not main_breaker_type or row_breaker == main_breaker_type) else 0.0
        metering_score = 1.0 if (not metering_type or row_metering == metering_type) else 0.0
        elcb_score = 1.0 if (not elcb_type or row_elcb == elcb_type) else 0.0

        # 가중 합산
        total = (
            kva_score * _WEIGHTS["kva"]
            + circuit_score * _WEIGHTS["circuit_count"]
            + breaker_score * _WEIGHTS["breaker_type"]
            + metering_score * _WEIGHTS["metering"]
            + elcb_score * _WEIGHTS["elcb_type"]
        )

        absolute_path = str(TEMPLATES_BASE_DIR / row["file_path"])

        scored.append({
            "sld_template_seq": row["sld_template_seq"],
            "phase": row["phase"],
            "kva": row_kva,
            "kva_diff": abs(row_kva - kva),
            "main_breaker_type": row_breaker,
            "circuit_count": row_circuit_count,
            "filename": row["filename"],
            "file_path": row["file_path"],
            "absolute_path": absolute_path,
            "detail": detail,
            "similarity_score": round(total, 4),
        })

    # 유사도 내림차순 정렬
    scored.sort(key=lambda x: x["similarity_score"], reverse=True)

    # fallback 임계값 적용
    if scored and scored[0]["similarity_score"] < _MIN_SIMILARITY_SCORE:
        logger.info(
            f"find_similar_templates: 최고 점수 {scored[0]['similarity_score']} < {_MIN_SIMILARITY_SCORE}, 빈 리스트 반환"
        )
        return []

    results = scored[:limit]

    logger.info(
        f"find_similar_templates: {len(results)}건 반환 "
        f"(phase={supply_type}, kva={kva}, top={results[0]['filename']} score={results[0]['similarity_score']})"
    )
    return results


# ── 기존 함수 (하위 호환) ────────────────────────────────

def find_best_template(spec: dict) -> dict | None:
    """하위 호환용. find_similar_templates의 상위 1개 반환."""
    results = find_similar_templates(spec, limit=1)
    return results[0] if results else None


def find_templates_by_spec(spec: dict, limit: int = 5) -> list[dict]:
    """하위 호환용. find_similar_templates으로 위임."""
    return find_similar_templates(spec, limit=limit)


# ── PDF → 이미지 변환 (Gemini Vision용) ─────────────────

def convert_pdf_to_image(pdf_path: str, dpi: int = 150) -> str | None:
    """
    PDF 첫 페이지를 PNG 이미지로 변환.

    Args:
        pdf_path: PDF 파일 절대 경로.
        dpi: 해상도 (기본 150 — 속도/품질 균형).

    Returns:
        PNG 이미지 경로. 실패 시 None.
    """
    try:
        import fitz  # PyMuPDF
    except ImportError:
        logger.error("PyMuPDF(fitz) 미설치: pip install PyMuPDF")
        return None

    if not os.path.exists(pdf_path):
        logger.error(f"PDF 파일 없음: {pdf_path}")
        return None

    # temp 디렉토리에 저장
    os.makedirs(settings.temp_file_dir, exist_ok=True)
    basename = Path(pdf_path).stem
    image_path = os.path.join(settings.temp_file_dir, f"template_{basename}.png")

    # 이미 변환된 캐시가 있으면 재사용
    if os.path.exists(image_path):
        return image_path

    try:
        doc = fitz.open(pdf_path)
        page = doc.load_page(0)
        mat = fitz.Matrix(dpi / 72, dpi / 72)
        pix = page.get_pixmap(matrix=mat)
        pix.save(image_path)
        doc.close()

        logger.info(f"PDF→이미지 변환 완료: {image_path} ({pix.width}x{pix.height})")
        return image_path

    except Exception as e:
        logger.error(f"PDF→이미지 변환 실패: {e}")
        return None


def load_image_as_base64(image_path: str) -> str | None:
    """이미지 파일을 base64 문자열로 로드."""
    if not image_path or not os.path.exists(image_path):
        return None
    try:
        with open(image_path, "rb") as f:
            return base64.b64encode(f.read()).decode("utf-8")
    except Exception as e:
        logger.error(f"이미지 base64 로드 실패: {e}")
        return None


# ── 템플릿 format → generator format 정규화 ────────────

def normalize_template_to_requirements(detail: dict) -> dict:
    """
    템플릿 detail_json을 generator.py가 기대하는 requirements 형식으로 변환.

    필드명 매핑:
        detail_json             →  requirements (generator)
        ─────────────────────────────────────────────────
        main_breaker.rating_a   →  main_breaker.rating
        main_breaker.ka_rating  →  main_breaker.fault_kA
        main_breaker.characteristic → main_breaker.breaker_characteristic
        sub_circuits[].description  →  sub_circuits[].name
        sub_circuits[].breaker_rating_a → sub_circuits[].breaker_rating
        sub_circuits[].breaker_ka  →  sub_circuits[].fault_kA
        sub_circuits[].breaker_characteristic → sub_circuits[].breaker_characteristic
        elcb.rating_a           →  elcb.rating
        elcb.sensitivity_ma     →  (유지)
        busbar.rating_a         →  busbar_rating

    Returns:
        generator.py가 직접 사용할 수 있는 requirements dict.
    """
    if not detail:
        return {}

    req = {}

    # 기본 필드
    req["supply_type"] = detail.get("supply_type", "three_phase")
    req["kva"] = detail.get("kva", 0)
    req["voltage"] = detail.get("voltage", 400)
    req["earth_protection"] = detail.get("earth_protection", True)

    # supply_source (landlord vs sp_powergrid)
    req["supply_source"] = detail.get("supply_source", "sp_powergrid")

    # metering
    metering_info = detail.get("metering", {})
    if isinstance(metering_info, dict):
        metering_type = metering_info.get("type")
        if metering_type:
            req["metering"] = metering_type
        elif req.get("supply_source") == "landlord":
            req["metering"] = None  # Landlord supply: no SP metering
        else:
            req["metering"] = "sp_meter"
    else:
        req["metering"] = "sp_meter"

    # main_breaker
    mb = detail.get("main_breaker", {})
    if isinstance(mb, dict):
        req["main_breaker"] = {
            "type": mb.get("type", "MCB"),
            "rating": mb.get("rating_a") or mb.get("rating", 0),
            "poles": mb.get("poles", ""),
            "fault_kA": mb.get("ka_rating") or mb.get("fault_kA", 0),
            "breaker_characteristic": mb.get("characteristic", ""),
        }
    else:
        req["main_breaker"] = {"type": "MCB", "rating": 0}

    # busbar
    busbar = detail.get("busbar", {})
    if isinstance(busbar, dict):
        req["busbar_rating"] = busbar.get("rating_a") or busbar.get("rating", 100)
    else:
        req["busbar_rating"] = 100

    # elcb
    elcb = detail.get("elcb", {})
    if isinstance(elcb, dict) and elcb.get("rating_a") or elcb.get("rating"):
        req["elcb"] = {
            "rating": elcb.get("rating_a") or elcb.get("rating", 0),
            "sensitivity_ma": elcb.get("sensitivity_ma", 30),
            "poles": elcb.get("poles", 4),
            "type": elcb.get("type", "ELCB") or "ELCB",
        }

    # incoming_cable
    incoming = detail.get("incoming_cable", {})
    if isinstance(incoming, dict):
        req["incoming_cable"] = incoming.get("description", "")
    elif isinstance(incoming, str):
        req["incoming_cable"] = incoming

    # sub_circuits
    raw_circuits = detail.get("sub_circuits", [])
    req["sub_circuits"] = []
    for sc in raw_circuits:
        if not isinstance(sc, dict):
            continue
        circuit = {
            "name": sc.get("description") or sc.get("name", ""),
            "breaker_type": sc.get("breaker_type", "MCB"),
            "breaker_rating": sc.get("breaker_rating_a") or sc.get("breaker_rating", 0),
            "breaker_characteristic": sc.get("breaker_characteristic", ""),
            "fault_kA": sc.get("breaker_ka") or sc.get("fault_kA", 6),
            "cable": sc.get("cable") or "",
        }
        req["sub_circuits"].append(circuit)

    return req
