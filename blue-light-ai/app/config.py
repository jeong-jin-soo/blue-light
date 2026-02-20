"""
Application configuration via environment variables.
Uses pydantic-settings for validation and type coercion.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # Gemini API
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.5-flash"
    gemini_max_tokens: int = 2048
    gemini_temperature: float = 0.3

    # Spring Boot Backend
    spring_boot_url: str = "http://localhost:8090"
    service_key: str = "dev-service-key"

    # Storage
    sqlite_db_path: str = "./data/checkpoints.db"
    temp_file_dir: str = "./temp"

    # Logging
    log_level: str = "INFO"

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
    }


settings = Settings()
