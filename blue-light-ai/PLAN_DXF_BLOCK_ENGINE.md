# DXF 블록 기반 SLD 엔진 전환 계획

## 목표

현재 19K줄의 절차적 심볼 드로잉 코드를 **레퍼런스 DXF 블록 기반 렌더링**으로 전환하여:
1. 심볼 형상의 원본 동일성 보장 (DXF INSERT = 100% 동일)
2. 매직넘버 제거 → DXF 실측값 기반 간격
3. 코드 복잡성 감소 (real_symbols.py 1,244줄 → BlockReplayer ~300줄)

## 현재 상태 (AS-IS)

### DXF 블록 사용 현황
- **사용 중**: MCCB, RCCB, DP ISOL (3개) — DxfBackend에서만 INSERT
- **미사용**: SLD-CT, VOLTMETER, 2A FUSE, SS, EF, 3P ISOL, LED IND LTG, 3P SOCKET (8개)
- **블록 없음**: MCB, KWH_METER, EARTH, FUSE, ELR, BI_CONNECTOR, METER, SELECTOR_SWITCH

### 렌더링 파이프라인
```
compute_layout() → PlacedComponent[] → generator._draw_components()
  → _draw_symbol_component()
    → DxfBackend + 블록 존재? → backend.insert_block()  (3개만)
    → 아니면? → symbol.draw(backend, x, y)  (real_symbols.py 절차적 드로잉)
```

### 핵심 발견
- **MCB와 MCCB는 같은 DXF 블록** (스케일만 다름: MCB=1.044, MCCB=1.278 등)
- PDF/SVG 백엔드는 항상 절차적 드로잉 (DXF 블록 활용 불가)
- LayoutConfig의 심볼 치수가 real_symbol_paths.json에서 로드됨

---

## 전환 계획

### Phase 0: DXF 인제스트 파이프라인 (Ingest Pipeline)
> 일회성 스크립트가 아닌, **새 DWG를 받을 때마다 반복 실행하는 파이프라인**

#### 설계 원칙

```
새 DWG 파일 수신
    │
    ▼
dwg2dxf 변환 (libredwg)
    │
    ▼
python -m app.sld.dxf_ingest scan         ← 단일 CLI 진입점
    │
    ├── 블록 추출 → dxf_block_library.json (증분 머지)
    ├── 간격 측정 → dxf_reference_spacing.json (증분 머지)
    └── 리포트 출력 → 신규/변경/동일 블록 현황
    │
    ▼
BlockReplayer 자동 반영 (JSON 리로드)
```

**핵심: 한 번 만들어 놓으면, 새 DWG가 올 때마다 `python -m app.sld.dxf_ingest scan` 한 줄이면 끝.**

#### 0-1. 파이프라인 모듈: `app/sld/dxf_ingest.py`

```python
"""DXF 인제스트 파이프라인.

새 DWG/DXF 파일을 분석하여 블록 라이브러리와 레퍼런스 간격 데이터를
증분 업데이트한다. 이미 분석된 파일은 건너뛰고, 새 파일만 처리한다.

Usage:
    # 전체 스캔 (신규 파일만 처리)
    python -m app.sld.dxf_ingest scan

    # 특정 파일 분석
    python -m app.sld.dxf_ingest scan --file "Sample 1.dwg"

    # 강제 전체 재스캔
    python -m app.sld.dxf_ingest scan --force

    # 현재 라이브러리 현황 출력
    python -m app.sld.dxf_ingest status

    # 블록 상세 조회
    python -m app.sld.dxf_ingest inspect MCCB
"""
```

#### 0-2. 데이터 구조: `dxf_block_library.json`

```jsonc
{
  "_meta": {
    "version": 2,
    "last_updated": "2026-03-14T15:30:00",
    "total_source_files": 28,        // 분석 완료된 DXF 수
    "total_blocks": 15               // 등록된 블록 수
  },

  // 처리 완료된 파일 매니페스트 (중복 스캔 방지)
  "_processed_files": {
    "100A TPN SLD 1 DWG.dxf": {
      "sha256": "a1b2c3...",         // 파일 해시 (변경 감지)
      "processed_at": "2026-03-14",
      "blocks_found": ["MCCB", "RCCB", "DP ISOL"],
      "sld_type": "direct_metering_3phase"
    },
    "150A TPN SLD 1 DWG.dxf": {
      "sha256": "d4e5f6...",
      "blocks_found": ["MCCB", "RCCB", "SLD-CT", "VOLTMETER", "2A FUSE", "SS", "EF", "LED IND LTG"],
      "sld_type": "ct_metering_3phase"
    }
    // 새 파일 추가 시 자동으로 엔트리 생성
  },

  // 블록 정의 (핵심 데이터)
  "blocks": {
    "MCCB": {
      "source_file": "150A TPN SLD 1 DWG.dxf",
      "source_count": 24,            // 이 블록이 존재하는 DXF 파일 수
      "width_du": 102.7,
      "height_du": 597.82,
      "bounds": {"min_x": -50.2, "min_y": 0.0, "max_x": 77.3, "max_y": 597.8},
      "entities": [
        {"type": "LWPOLYLINE", "points": [[0,0],[0,597.82]], "bulges": [0.611, 0]},
        {"type": "CIRCLE", "center": [-0.8, 548.3], "radius": 49.47},
        {"type": "CIRCLE", "center": [0.0, 49.5], "radius": 49.47}
      ],
      "pins": {
        "top": [0, 597.82],
        "bottom": [0, 0]
      }
    },
    "SLD-CT": { "..." : "..." },
    "VOLTMETER": { "..." : "..." }
  },

  // 커스텀 블록 (DXF에 블록 정의가 없어 모델스페이스에서 수동 정의)
  "custom_blocks": {
    "KWH_METER": {
      "source": "150A TPN modelspace, manually identified",
      "width_du": 1068, "height_du": 533,
      "entities": [ "..." ],
      "pins": { "left": [0, 266], "right": [1068, 266] }
    },
    "EARTH": { "..." : "..." }
  }
}
```

#### 0-3. 데이터 구조: `dxf_reference_spacing.json`

```jsonc
{
  "_meta": {
    "version": 1,
    "last_updated": "2026-03-14T15:30:00"
  },

  // SLD 유형별 실측 간격 (새 DWG 분석 시 자동 추가)
  "profiles": {
    "ct_metering_3phase": {
      "source_files": ["150A TPN SLD 1 DWG.dxf", "400A TPN SLD 1 DWG.dxf"],
      "spine_components": [
        {"name": "MCCB", "y_du": 7244.0, "scale": 1.162},
        {"name": "SLD-CT_prot", "y_du": 8866.0, "scale": 6.067},
        {"name": "SLD-CT_meter", "y_du": 10016.4, "scale": 6.067}
      ],
      "spine_gaps_du": {
        "mccb_to_prot_ct": 1622.0,
        "prot_ct_to_meter_ct": 1150.4
      },
      "subcircuit_x_spacing_du": {"min": 600, "max": 600, "samples": 28},
      "subcircuit_mccb_scale": 1.278,
      "busbar_y_du": 19004.2,
      "viewport_scale_mm_per_du": 0.009
    },
    "direct_metering_3phase": {
      "source_files": ["100A TPN SLD 1 DWG.dxf"],
      "subcircuit_x_spacing_du": {"min": 713.7, "max": 726.8, "samples": 25},
      "subcircuit_mccb_scale": 1.044,
      "main_mccb_scale": 1.190
    },
    "direct_metering_1phase": {
      "source_files": ["32A SP SLD 1 DWG.dxf"],
      "..." : "..."
    }
    // 새 유형의 DWG 분석 시 자동 프로파일 추가
  }
}
```

#### 0-4. 인제스트 파이프라인 핵심 로직

```python
class DxfIngestPipeline:
    """DXF 파일 분석 → 블록 라이브러리 + 간격 데이터 증분 업데이트."""

    LIBRARY_PATH = "data/templates/dxf_block_library.json"
    SPACING_PATH = "data/templates/dxf_reference_spacing.json"
    DXF_DIR = "data/sld-info/slds-dxf"
    DWG_DIR = "data/sld-info/slds-dwg"

    def scan(self, target_file: str | None = None, force: bool = False):
        """메인 스캔 루프."""
        library = self._load_or_init(self.LIBRARY_PATH)
        spacing = self._load_or_init(self.SPACING_PATH)

        files = self._discover_files(target_file)
        for dxf_path in files:
            file_hash = self._sha256(dxf_path)

            # 이미 처리된 파일이고 해시 동일 → 건너뜀
            if not force and self._already_processed(library, dxf_path, file_hash):
                continue

            print(f"[SCAN] {dxf_path.name} ...")

            # Step 1: 블록 추출
            new_blocks = self._extract_blocks(dxf_path)
            merged_blocks = self._merge_blocks(library, new_blocks, dxf_path)

            # Step 2: 모델스페이스 간격 측정
            spacing_profile = self._measure_spacing(dxf_path)
            if spacing_profile:
                self._merge_spacing(spacing, spacing_profile)

            # Step 3: 매니페스트 업데이트
            self._update_manifest(library, dxf_path, file_hash, new_blocks)

            print(f"  → 블록 {len(new_blocks)}개 (신규 {merged_blocks['new']}, 기존 {merged_blocks['existing']})")

        self._save(self.LIBRARY_PATH, library)
        self._save(self.SPACING_PATH, spacing)

    def _merge_blocks(self, library, new_blocks, source_file):
        """블록 머지 정책: 엔티티 수가 더 많은 쪽 우선 (더 풍부한 정의)."""
        stats = {"new": 0, "existing": 0}
        for name, block_def in new_blocks.items():
            existing = library["blocks"].get(name)
            if not existing:
                library["blocks"][name] = block_def
                stats["new"] += 1
            else:
                # 기존 블록과 기하 비교: height_du ±1% 이내면 동일
                if abs(existing["height_du"] - block_def["height_du"]) / existing["height_du"] < 0.01:
                    existing["source_count"] = existing.get("source_count", 1) + 1
                else:
                    # 치수가 다른 블록 → 변형으로 등록 (예: MCCB_v2)
                    variant_name = f"{name}__{source_file.stem}"
                    library["blocks"][variant_name] = block_def
                    print(f"  ⚠ {name} 치수 상이 → {variant_name}으로 변형 등록")
                stats["existing"] += 1
        return stats

    def _classify_sld_type(self, dxf_path) -> str:
        """파일 이름과 블록 구성으로 SLD 유형 자동 분류."""
        name = dxf_path.stem.upper()
        doc = ezdxf.readfile(str(dxf_path))
        block_names = {b.name for b in doc.blocks if not b.name.startswith('*') and list(b)}

        if "SLD-CT" in block_names:
            return "ct_metering_3phase"
        elif "CABLE EXTENSION" in name:
            return "cable_extension"
        elif "SP" in name or "SINGLE" in name:
            return "direct_metering_1phase"
        elif "TPN" in name:
            return "direct_metering_3phase"
        else:
            return "unknown"
```

#### 0-5. DWG→DXF 자동 변환

```python
def _discover_files(self, target_file=None):
    """DWG 디렉토리에 새 파일 있으면 자동 DXF 변환."""
    dwg_files = list(Path(self.DWG_DIR).glob("*.dwg"))
    for dwg in dwg_files:
        dxf = Path(self.DXF_DIR) / dwg.with_suffix('.dxf').name
        if not dxf.exists():
            print(f"[CONVERT] {dwg.name} → DXF")
            subprocess.run(["dwg2dxf", str(dwg)], check=True)
            shutil.move(dwg.with_suffix('.dxf'), dxf)

    # 스캔 대상 결정
    if target_file:
        return [Path(self.DXF_DIR) / target_file]
    return sorted(Path(self.DXF_DIR).glob("*.dxf"))
```

#### 0-6. CLI 인터페이스

```bash
# 일상적 사용 (새 DWG 받았을 때)
$ cp "새파일.dwg" data/sld-info/slds-dwg/
$ python -m app.sld.dxf_ingest scan

[CONVERT] 새파일.dwg → DXF
[SCAN] 새파일.dxf ...
  → 블록 5개 (신규 2, 기존 3)
  → 간격 프로파일: direct_metering_3phase (기존 업데이트)
  ✅ dxf_block_library.json 업데이트 (15 → 17 블록)
  ✅ dxf_reference_spacing.json 업데이트

# 현황 확인
$ python -m app.sld.dxf_ingest status

DXF Block Library Status
========================
  처리된 파일: 28개
  등록 블록: 17개
  커스텀 블록: 3개

  블록별 현황:
    MCCB          | 26/28 파일 | 597.82 DU
    RCCB          | 21/28 파일 | 597.82 DU
    DP ISOL       | 18/28 파일 | 430.63 DU
    SLD-CT        |  3/28 파일 |  50.00 DU  (신규!)
    NEW_SYMBOL    |  1/28 파일 | 200.00 DU  (신규!)
    ...

  미처리 파일: 0개

# 특정 블록 상세
$ python -m app.sld.dxf_ingest inspect MCCB

Block: MCCB
  Size: 102.7 × 597.82 DU
  Source: 150A TPN SLD 1 DWG.dxf (26개 파일에서 확인)
  Entities: 3 (LWPOLYLINE:1, CIRCLE:2)
  Pins: top=[0, 597.82], bottom=[0, 0]
  DXF Scale Usage:
    서브회로: 1.044 (100A), 1.278 (150A), 1.406 (63A)
    메인: 1.162 (150A), 1.190 (100A)
```

#### 0-7. 산출물 요약

| 산출물 | 용도 | 갱신 주기 |
|--------|------|----------|
| `app/sld/dxf_ingest.py` | 인제스트 파이프라인 모듈 | 코드 (한 번 구현) |
| `data/templates/dxf_block_library.json` | 블록 엔티티 + 핀 + 치수 | 새 DWG마다 증분 갱신 |
| `data/templates/dxf_reference_spacing.json` | SLD 유형별 실측 간격 | 새 DWG마다 증분 갱신 |

#### 0-8. 커스텀 블록 관리

DXF에 블록 정의가 없는 심볼 (KWH, EARTH 등)은:
1. 인제스트 시 자동 검출 불가 → `custom_blocks` 섹션에 수동 등록
2. 새 DWG에서 해당 심볼의 블록이 발견되면 자동으로 `blocks`로 승격
3. `dxf_ingest.py`에 `register-custom` 서브커맨드로 수동 등록 지원

```bash
# 커스텀 블록 등록 (모델스페이스 좌표 기반)
$ python -m app.sld.dxf_ingest register-custom \
    --name KWH_METER \
    --source "150A TPN SLD 1 DWG.dxf" \
    --region "23958,11424,25026,11958"  # x1,y1,x2,y2 바운딩 박스
```

**검증:** `python -m app.sld.dxf_ingest status` 로 전체 현황 확인, `inspect` 로 개별 블록 상세 확인

---

### Phase 1: BlockReplayer 시스템 구축
> DXF 블록 엔티티를 모든 DrawingBackend에서 재생

#### 1-1. `app/sld/block_replayer.py` 신규 생성

```python
class BlockReplayer:
    """DXF 블록 엔티티를 DrawingBackend 프리미티브로 재생.

    - DxfBackend: insert_block() 직접 사용 (원본 100% 동일)
    - PdfBackend/SvgBackend: 엔티티를 프리미티브로 변환하여 재생
    """

    def __init__(self, block_library: dict):
        """block_library: dxf_block_library.json 로드 결과."""
        self._blocks = block_library

    def draw(self, backend, block_name: str, x: float, y: float,
             *, scale: float = 1.0, rotation: float = 0.0,
             skip_trip_arrow: bool = False, **kwargs) -> None:
        """블록을 지정 위치에 렌더링."""

        if isinstance(backend, DxfBackend) and backend.has_block(block_name):
            # DXF 출력: 원본 블록 INSERT (100% 동일)
            dxf_scale = self._compute_dxf_scale(block_name, scale)
            backend.insert_block(block_name, x, y, scale=dxf_scale, rotation=rotation)
        else:
            # PDF/SVG 출력: 엔티티 프리미티브 재생
            self._replay_entities(backend, block_name, x, y, scale, rotation, **kwargs)

    def _replay_entities(self, backend, block_name, x, y, scale, rotation, **kwargs):
        """블록의 엔티티를 DrawingBackend 프리미티브로 변환."""
        block_def = self._blocks[block_name]
        for entity in block_def["entities"]:
            # 좌표 변환: 블록 로컬 → 페이지 글로벌 (scale + rotation + translate)
            if entity["type"] == "CIRCLE":
                cx, cy = self._transform(entity["center"], x, y, scale, rotation)
                backend.add_circle((cx, cy), entity["radius"] * scale)
            elif entity["type"] == "LINE":
                s = self._transform(entity["start"], x, y, scale, rotation)
                e = self._transform(entity["end"], x, y, scale, rotation)
                backend.add_line(s, e)
            elif entity["type"] == "ARC":
                cx, cy = self._transform(entity["center"], x, y, scale, rotation)
                backend.add_arc((cx, cy), entity["radius"] * scale,
                               entity["start_angle"] + rotation,
                               entity["end_angle"] + rotation)
            elif entity["type"] == "LWPOLYLINE":
                # bulge 처리 (원호 세그먼트 포함)
                self._replay_lwpolyline(backend, entity, x, y, scale, rotation)

    def get_pins(self, block_name: str, x: float, y: float,
                 scale: float = 1.0) -> dict[str, tuple[float, float]]:
        """블록의 연결 핀 위치를 페이지 좌표로 반환."""
        block_def = self._blocks[block_name]
        pins = {}
        for pin_name, local_pos in block_def["pins"].items():
            pins[pin_name] = (x + local_pos[0] * scale, y + local_pos[1] * scale)
        return pins
```

#### 1-2. 핵심 설계 결정사항

**인제스트 파이프라인 연동:**
- BlockReplayer는 `dxf_block_library.json`을 로드하여 초기화
- JSON이 갱신되면 (새 DWG 인제스트) 자동 반영 (서버 재시작 시 리로드)
- 새 블록이 추가되면 `_SYMBOL_TO_DXF_BLOCK` 매핑만 추가하면 즉시 사용
- 커스텀 블록도 동일한 인터페이스로 처리 (JSON의 `custom_blocks` 섹션)

**좌표 변환:**
- DXF 블록은 Drawing Unit(DU) 좌표계, 우리 레이아웃은 mm 좌표계
- `scale = target_height_mm / block_height_du` 로 변환
- 예: MCCB 15mm 높이 → scale = 15.0 / 597.82 = 0.0251

**핀(연결점) 시스템:**
- 각 블록에 `top`, `bottom`, `left`, `right` 핀 정의
- 연결선 = 핀 A → 핀 B (좌표 계산 불필요)
- 핀 위치는 인제스트 시 DXF에서 자동 추출 (Phase 0)

**trip arrow 처리:**
- RCD bar (RCCB의 수평선+수직선)는 RCCB 블록 내에 이미 포함
- trip arrow는 블록 외부에서 별도 드로잉 (서브회로 ditto 시 생략 필요)
- `skip_trip_arrow` 파라미터 유지

**DxfBackend 특별 처리:**
- `insert_block()` 사용 시 엔티티 재생 불필요
- scale 파라미터는 DU→DU 스케일 (mm 변환 불필요)
- 현재 `_DXF_BLOCK_HEIGHTS` 로직 그대로 유지
- 인제스트된 모든 블록을 자동 import (현재 3개 → 전체)

#### 1-3. 테스트

```python
# tests/test_block_replayer.py
def test_mccb_replay_matches_original():
    """BlockReplayer의 MCCB 재생이 RealMCCB.draw()와 동일한지."""

def test_dxf_backend_uses_insert():
    """DxfBackend에서는 insert_block()을 사용하는지."""

def test_pins_correct():
    """핀 위치가 정확한지."""
```

---

### Phase 2: 블록 라이브러리 확장 및 심볼 통합
> MCCB 3개 → 전체 11+ 블록 활성화

#### 2-1. DXF 블록 import 확장

**현재** (`dxf_backend.py:import_symbol_blocks()`):
```python
target_blocks = ["MCCB", "RCCB", "DP ISOL"]
```

**변경:**
```python
target_blocks = [
    "MCCB", "RCCB", "DP ISOL",        # 기존 (24/27, 19/27, 16/27 파일)
    "SLD-CT", "VOLTMETER",             # CT 계측용 (150A, 400A TPN)
    "2A FUSE", "SS", "EF",             # CT 계측 보조 (150A, 400A TPN)
    "3P ISOL",                          # 3극 아이솔레이터 (63A TPN)
    "LED IND LTG",                      # 표시등 (150A, 400A TPN)
    "3P SOCKET",                        # 3P 소켓 (Cable Extension)
]
```

**레퍼런스 DXF 선택 로직:**
- 기본: `100A TPN SLD 1 DWG.dxf` (MCCB, RCCB, DP ISOL)
- CT 계측: `150A TPN SLD 1 DWG.dxf` 추가 로드 (SLD-CT, VOLTMETER 등)
- Cable Extension: `Cable Extension SLD 4 DWG.dxf` 추가 로드 (3P SOCKET)

#### 2-2. MCB → MCCB 블록 통합

**핵심 발견:** 레퍼런스 DXF에서 MCB는 별도 블록이 없음. MCCB 블록을 다른 스케일로 사용.

| 용도 | 블록 | 스케일 (100A TPN) | 스케일 (150A TPN) |
|------|------|-------------------|-------------------|
| 서브회로 MCB | MCCB | 1.044 | 1.278 |
| 메인 MCCB | MCCB | 1.190 | 1.162 |
| 메인 (수평) | MCCB | 1.288 (rot=90°) | — |

**변경 사항:**
- `_SYMBOL_TO_DXF_BLOCK`에 `"MCB": "MCCB"`, `"CB_MCB": "MCCB"` 추가
- `RealMCB` 클래스 → BlockReplayer로 대체 (MCCB 블록 + MCB 스케일)
- **차이점 주의**: MCB arc sweep = 151.4°, MCCB = 125.8° — DXF 블록은 MCCB 형상이므로 MCB도 MCCB 형상으로 통일 (레퍼런스 DXF가 실제로 이렇게 하므로)

#### 2-3. KWH_METER 커스텀 블록

DXF에 KWH 블록이 없으므로, 150A TPN 모델스페이스에서 측정한 치수로 커스텀 정의:

```json
{
  "KWH_METER": {
    "custom": true,
    "source": "150A TPN modelspace LWPOLYLINE at (23958,11424)-(25026,11958)",
    "width_du": 1068, "height_du": 533,
    "entities": [
      {"type": "LWPOLYLINE", "points": [[0,0],[1068,0],[1068,533],[0,533]], "closed": true},
      {"type": "CIRCLE", "center": [534, 266], "radius": 200}
    ],
    "pins": {
      "left": [0, 266],
      "right": [1068, 266],
      "top": [534, 533],
      "bottom": [534, 0]
    }
  }
}
```

#### 2-4. EARTH 커스텀 블록

150A TPN에서 loose CIRCLE 4개 + 연결선 패턴:

```json
{
  "EARTH": {
    "custom": true,
    "entities": [
      {"type": "CIRCLE", "center": [0, 0], "radius": 70.8},
      {"type": "CIRCLE", "center": [567.8, 0], "radius": 70.8},
      {"type": "LINE", "start": [70.8, 0], "end": [497.0, 0]}
    ],
    "pins": {
      "top": [283.9, 70.8],
      "left": [-70.8, 0],
      "right": [638.6, 0]
    }
  }
}
```

---

### Phase 3: generator.py 통합
> _draw_symbol_component()에서 BlockReplayer 우선 사용

#### 3-1. 렌더링 우선순위 변경

```python
# 현재:
# 1. DxfBackend + 블록 있음? → insert_block()  (MCCB/RCCB/DP ISOL만)
# 2. 아니면 → symbol.draw()  (real_symbols.py)

# 변경:
# 1. BlockReplayer에 블록 정의 있음? → replayer.draw()
#    - DxfBackend → insert_block()
#    - PDF/SVG → 엔티티 프리미티브 재생
# 2. 아니면 → symbol.draw()  (fallback)
```

#### 3-2. _SYMBOL_TO_DXF_BLOCK 확장

```python
_SYMBOL_TO_DXF_BLOCK = {
    # 기존
    "MCCB": "MCCB",   "CB_MCCB": "MCCB",
    "RCCB": "RCCB",   "CB_RCCB": "RCCB",
    "ELCB": "RCCB",   "CB_ELCB": "RCCB",
    # 추가
    "MCB": "MCCB",    "CB_MCB": "MCCB",        # MCB = MCCB 블록 + 다른 스케일
    "ISOLATOR": "DP ISOL",                       # 이미 존재하지만 미매핑
    "CT": "SLD-CT",                              # CT 계측용
    "KWH_METER": "KWH_METER",                   # 커스텀 블록
    "EARTH": "EARTH",                            # 커스텀 블록
    "FUSE": "2A FUSE",   "POTENTIAL_FUSE": "2A FUSE",
    "VOLTMETER": "VOLTMETER",
    "SELECTOR_SWITCH": "SS",
    "ELR": "EF",                                 # Earth Fault relay
    "INDICATOR_LIGHTS": "LED IND LTG",
    "3P_ISOLATOR": "3P ISOL",
}
```

#### 3-3. 스케일 계산 통합

```python
# 현재: _DXF_BLOCK_HEIGHTS 딕셔너리 + symbol.height로 스케일 계산
# 변경: BlockReplayer가 내부적으로 처리
#   mm_scale = target_height_mm / block_height_du
#   dxf_scale = mm_scale / viewport_scale  (DXF INSERT용)
```

---

### Phase 4: 매직넘버 → DXF 실측값 전환
> LayoutConfig의 하드코딩 값을 실측 데이터로 교체

#### 4-1. LayoutConfig.__post_init__() 확장

현재 `real_symbol_paths.json`에서 치수를 로드하는 것처럼,
`dxf_reference_spacing.json`에서 간격값도 로드:

```python
def __post_init__(self):
    # 기존: real_symbol_paths.json에서 심볼 치수
    # 추가: dxf_reference_spacing.json에서 레이아웃 간격
    try:
        spacing = load_json("dxf_reference_spacing.json")
        ct_ref = spacing.get("ct_metering_3phase", {})
        self.ct_entry_gap = ct_ref.get("mccb_to_prot_ct_mm", 0.5)
        self.ct_to_ct_gap = ct_ref.get("prot_ct_to_meter_ct_mm", 3.0)
        # ...
    except Exception:
        pass  # fallback to hardcoded defaults
```

#### 4-2. 교체 대상 매직넘버 목록

| 현재 위치 | 현재 값 | DXF 실측 근거 | 교체 방법 |
|-----------|---------|---------------|----------|
| sections.py `entry_gap` | 0.5mm | MCCB→ProtCT Y gap | `dxf_reference_spacing.json` |
| sections.py `ct_to_ct_gap` | 3.0mm | ProtCT→MeterCT Y gap | 상동 |
| sections.py `branch_arm_len` | 15.0mm | spine X → branch comp X 거리 | 상동 |
| sections.py `branch_gap` | 3.0mm | ASS→Ammeter 간격 | 상동 |
| sections.py `_seg_h=14` (incoming supply) | 14mm | 인입부 수직 길이 | 상동 |
| models.py `meter_board_comp_spacing` | 25.0mm | ISO→KWH→MCB X 간격 | 상동 |
| models.py `horizontal_spacing` | 26mm | 서브회로 열 간격 | subcircuit_x_spacing |
| models.py `earth_y_below_busbar` | 25.0mm | Earth Y 위치 | 상동 |

---

### Phase 5: 검증 및 스냅샷 업데이트
> 기존 품질 유지 확인

#### 5-1. 기존 테스트 실행
```bash
pytest tests/test_svg_snapshots.py -v      # 11개 스냅샷 회귀 테스트
pytest tests/test_spine_flow_order.py -v    # CT 스파인 순서 검증
pytest tests/test_multi_db.py -v            # 멀티 DB 레이아웃
```

#### 5-2. 스냅샷 업데이트
- BlockReplayer 기반 심볼은 기존과 미세 차이 예상 (DXF 정밀 치수 vs 코드 근사치)
- `UPDATE_SNAPSHOTS=1 pytest` 로 새 golden file 생성
- 변경 전후 SVG diff 리뷰

#### 5-3. 레퍼런스 PDF 대비 검증
4개 기존 케이스 각각:
1. 생성된 PDF vs 레퍼런스 PDF 시각 비교
2. DXF 출력을 AutoCAD/LibreCAD에서 열어 블록 정합성 확인
3. 심볼 크기/비율이 레퍼런스와 일치하는지 확인

#### 5-4. Gemini Vision 자동 비교 (선택)
```python
# scripts/compare_sld_vision.py
# 생성 PDF vs 레퍼런스 PDF → Gemini Vision API → 차이점 리포트
```

---

## 실행 순서 및 의존성

```
Phase 0 (인제스트 파이프라인)  ←── 모든 Phase의 기반, 이후 지속 사용
    │
    ├── 0-1~0-5: dxf_ingest.py 파이프라인 구현
    ├── 0-6: CLI 인터페이스 (scan / status / inspect)
    ├── 0-7: 초기 27개 DXF 전수 스캔 실행
    └── 0-8: KWH/EARTH 커스텀 블록 수동 등록
    │
    ▼
Phase 1 (BlockReplayer)  ←── Phase 0 JSON 산출물 사용
    │
    ├── 1-1: BlockReplayer 클래스 구현 (JSON 로드 → 렌더링)
    ├── 1-2: 좌표 변환 + 핀 시스템
    └── 1-3: 단위 테스트
    │
    ▼
Phase 2 (블록 확장)  ←── Phase 1 위에 구축
    │
    ├── 2-1: import_symbol_blocks() 확장 (인제스트 JSON 기반)
    ├── 2-2: MCB → MCCB 통합
    ├── 2-3: KWH 커스텀 블록
    └── 2-4: EARTH 커스텀 블록
    │
    ▼
Phase 3 (generator 통합)  ←── Phase 1+2 결과 투입
    │
    ├── 3-1: 렌더링 우선순위 변경
    ├── 3-2: 매핑 확장
    └── 3-3: 스케일 계산 통합
    │
    ▼
Phase 4 (매직넘버 교체)  ←── Phase 0 간격 데이터 사용
    │
    ├── 4-1: LayoutConfig 확장
    └── 4-2: sections.py 매직넘버 교체
    │
    ▼
Phase 5 (검증)
    │
    ├── 5-1: 기존 테스트 통과
    ├── 5-2: 스냅샷 업데이트
    ├── 5-3: 레퍼런스 PDF 대비 검증
    └── 5-4: (선택) Gemini Vision 자동 비교
```

### 새 DWG 수신 시 운영 플로우 (Phase 0 완료 후)

```
새 DWG 파일 수신
    │
    ▼
$ cp "새파일.dwg" data/sld-info/slds-dwg/
$ python -m app.sld.dxf_ingest scan
    │
    ├── 자동: DWG→DXF 변환
    ├── 자동: 블록 추출 + 기존 라이브러리에 증분 머지
    ├── 자동: 간격 프로파일 측정 + 기존 데이터에 증분 머지
    └── 출력: 신규/변경/동일 블록 리포트
    │
    ▼
신규 블록 발견?
    │
    ├── 기존 블록과 동일 → 자동 처리 완료, source_count 증가
    ├── 새 블록 → dxf_block_library.json에 자동 등록
    │   └── _SYMBOL_TO_DXF_BLOCK 매핑 추가 (1줄)
    └── 기존 블록과 치수 상이 → 변형(variant)으로 등록, 리뷰 필요
    │
    ▼
BlockReplayer 자동 반영 (서버 재시작 시 JSON 리로드)
```

## 리스크 및 대응

| 리스크 | 영향 | 대응 |
|--------|------|------|
| LWPOLYLINE bulge 재생 정확도 | 호(arc) 세그먼트가 약간 다를 수 있음 | bulge→arc 변환 공식 검증 (ezdxf 유틸 활용) |
| PDF/SVG에서 DXF 블록 재생 시 미세 차이 | 스냅샷 테스트 실패 | 허용 오차 설정 또는 float 정밀도 조정 |
| 일부 심볼 (ELR, BI_CONNECTOR)이 어떤 DXF에도 블록 없음 | 커스텀 블록 필요 | 150A TPN 모델스페이스에서 엔티티 좌표 추출하여 커스텀 정의 |
| CT 계측 SLD-CT 블록이 2/27 파일에만 존재 | 특정 레퍼런스 의존 | 150A TPN을 CT 계측 블록 소스로 고정 |
| real_symbols.py 제거 시 기존 API 깨짐 | get_real_symbol() 호출처 전부 영향 | Phase 3에서 점진 교체, fallback 유지 |

## 변경하지 않는 것

- **DrawingBackend 프로토콜** — 그대로 유지
- **compute_layout() → PlacedComponent[] 파이프라인** — 그대로 유지
- **Gemini 통합 (agent/graph, tools, prompts)** — 영향 없음
- **template_matcher, template_merge** — 영향 없음
- **sld_spec.py 검증 규칙** — 영향 없음
- **connections/busbar/annotation 드로잉** — 심볼만 교체, 연결선은 기존 방식 유지

## 성공 기준

1. 기존 11개 스냅샷 테스트 통과 (허용 오차 내)
2. DXF 출력에서 모든 심볼이 INSERT 엔티티 (LINE/ARC 아닌)
3. `real_symbols.py`의 draw() 호출 0건 (BlockReplayer로 완전 대체)
4. 4개 기존 케이스 PDF가 레퍼런스와 시각적으로 동일
5. LayoutConfig에서 매직넘버 50% 이상 제거 (DXF 실측값으로 대체)
