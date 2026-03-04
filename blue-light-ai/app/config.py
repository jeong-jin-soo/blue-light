"""
Application configuration via environment variables.
Uses pydantic-settings for validation and type coercion.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    # Gemini API
    gemini_api_key: str = ""
    gemini_model: str = "gemini-3-flash-preview"
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
    mysql_port: int = 3307
    mysql_database: str = "bluelight"
    mysql_user: str = "user"
    mysql_password: str = "password"

    # Environment (development | production)
    environment: str = "development"

    # Logging
    log_level: str = "INFO"

    model_config = {
        "env_file": ".env",
        "env_file_encoding": "utf-8",
    }


settings = Settings()
