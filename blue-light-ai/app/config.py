"""
Application configuration via environment variables.
Uses pydantic-settings for validation and type coercion.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # Gemini API
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.5-flash"              # 대화/에이전트용
    gemini_pro_model: str = "gemini-2.5-pro"             # 스펙 추출 + Vision 검증용
    gemini_fallback_model: str = "gemini-2.5-flash-lite"  # 503/429 폴백용
    gemini_max_tokens: int = 8192
    gemini_temperature: float = 0.3

    # Spring Boot Backend
    spring_boot_url: str = "http://localhost:8090"
    service_key: str = "dev-service-key"

    # Storage
    sqlite_db_path: str = "./data/db/checkpoints.db"
    temp_file_dir: str = "./temp"

    # MySQL (SLD 템플릿 DB)
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_database: str = "bluelight"
    mysql_user: str = "user"
    mysql_password: str = "password"

    # Layout Optimizer (3-Tier)
    layout_optimizer_enabled: bool = False  # LAYOUT_OPTIMIZER_ENABLED=true로 활성화

    # SSE 타임아웃 정합 (단위: 초)
    # - sse_heartbeat_interval: AI service가 SSE 응답에 keepalive 이벤트를 보내는 주기.
    #   짧으면 트래픽↑, 길면 ReadTimeout 위험↑. 기본 15s.
    # - sse_emitter_timeout_seconds: Spring Boot SseEmitter / WebClient ReadTimeout과
    #   동일해야 한다. 한쪽만 길면 다른 쪽이 끊겨 클라이언트가 무응답을 본다.
    #   참고 — backend `sld.agent.timeout-seconds` 와 정렬할 것.
    sse_heartbeat_interval: int = 15
    sse_emitter_timeout_seconds: int = 600

    # Environment (development | production)
    environment: str = "development"

    # Logging
    log_level: str = "INFO"

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
    }


settings = Settings()
