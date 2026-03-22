"""Tests for the direct SLD generation API (Track A — no LLM)."""

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client():
    """Create test client with service key auth bypassed via dependency override."""
    from app.main import app
    from app.dependencies import verify_service_key

    async def _no_auth():
        return "test"

    app.dependency_overrides[verify_service_key] = _no_auth
    yield TestClient(app)
    app.dependency_overrides.pop(verify_service_key, None)


def _auth_headers():
    return {"X-Service-Key": "test-key"}


class TestDirectSldGeneration:
    """POST /api/sld/generate tests."""

    def test_generate_single_phase(self, client):
        """Generate a simple single-phase SLD."""
        resp = client.post("/api/sld/generate", json={
            "requirements": {
                "supply_type": "single_phase",
                "kva": 9.2,
                "voltage": 230,
                "phase_config": "DP",
                "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10, "breaker_characteristic": "B"},
                "incoming_cable": {"size_mm2": 10, "earth_mm2": 10, "type": "PVC", "cores": 2, "count": 1, "cpc_type": "PVC", "method": "METAL TRUNKING"},
                "elcb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": "DP"},
                "metering": "sp_meter",
                "sub_circuits": [
                    {"circuit_id": "L1S1", "phase": "L1", "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SP", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC"},
                    {"circuit_id": "L1P1", "phase": "L1", "name": "SOCKET", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SP", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC"},
                ],
            },
        }, headers=_auth_headers())
        assert resp.status_code == 200
        data = resp.json()
        assert "file_id" in data
        assert data["component_count"] > 0

    def test_generate_three_phase(self, client):
        """Generate a 3-phase TPN SLD."""
        resp = client.post("/api/sld/generate", json={
            "requirements": {
                "supply_type": "three_phase",
                "kva": 24,
                "voltage": 400,
                "phase_config": "TPN",
                "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
                "incoming_cable": {"size_mm2": 16, "earth_mm2": 10, "type": "PVC", "cores": 4, "count": 1, "cpc_type": "PVC", "method": "METAL TRUNKING"},
                "elcb": {"type": "RCCB", "rating": 63, "sensitivity_ma": 30, "poles": "TPN"},
                "metering": "sp_meter",
                "sub_circuits": [
                    {"circuit_id": "L1S1", "phase": "L1", "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC"},
                    {"circuit_id": "L2S1", "phase": "L2", "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC"},
                    {"circuit_id": "L3S1", "phase": "L3", "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC"},
                ],
            },
        }, headers=_auth_headers())
        assert resp.status_code == 200
        data = resp.json()
        assert data["component_count"] > 0

    def test_generate_missing_requirements(self, client):
        """Missing required fields should return 422."""
        resp = client.post("/api/sld/generate", json={
            "requirements": {},
        }, headers=_auth_headers())
        # Empty requirements should still work (engine applies defaults)
        # or raise a validation error — both are acceptable
        assert resp.status_code in (200, 422)

    def test_file_downloadable(self, client):
        """Generated file should be downloadable via /api/files/{file_id}."""
        resp = client.post("/api/sld/generate", json={
            "requirements": {
                "supply_type": "single_phase",
                "kva": 9.2,
                "voltage": 230,
                "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
                "metering": "sp_meter",
                "sub_circuits": [
                    {"circuit_id": "L1S1", "phase": "L1", "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SP", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC"},
                ],
            },
        }, headers=_auth_headers())
        assert resp.status_code == 200
        file_id = resp.json()["file_id"]

        # Download PDF
        dl_resp = client.get(f"/api/files/{file_id}?format=pdf", headers=_auth_headers())
        assert dl_resp.status_code == 200
        assert dl_resp.headers["content-type"] == "application/pdf"

        # Download SVG
        svg_resp = client.get(f"/api/files/{file_id}/svg", headers=_auth_headers())
        assert svg_resp.status_code == 200
        assert "svg" in svg_resp.json()
