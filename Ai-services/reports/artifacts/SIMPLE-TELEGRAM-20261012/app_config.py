"""Explicit, side-effect-free configuration for the AI service foundation."""

from __future__ import annotations

from pathlib import Path

from pydantic import SecretStr, ValidationError, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict, SettingsError


class Settings(BaseSettings):
    """Environment-backed settings; constructing this class never loads a .env file."""

    model_config = SettingsConfigDict(
        env_file=None,
        extra="ignore",
        frozen=True,
        hide_input_in_errors=True,
    )

    app_env: str = "development"
    app_host: str = "127.0.0.1"
    app_port: int = 8001
    log_level: str = "INFO"
    allowed_origins: list[str] = []
    chat_max_message_chars: int = 4000
    chat_max_pending_messages: int = 100
    chat_websocket_heartbeat_seconds: int = 30

    ai_provider: str = "openrouter"
    ai_model: str = ""
    openrouter_api_key: SecretStr | None = None
    ai_max_output_tokens: int = 1024
    ai_request_timeout_seconds: float = 60.0
    ai_max_graph_steps: int = 12
    ai_temperature: float = 0.2

    auth_jwks_url: str = ""
    auth_jwt_issuer: str = ""
    auth_jwt_audience: str = ""

    spring_grpc_target: str = "localhost:9090"
    spring_grpc_tls_enabled: bool = True
    spring_grpc_ca_file: str = ""
    spring_grpc_client_cert_file: str = ""
    spring_grpc_client_key_file: str = ""
    spring_grpc_timeout_seconds: float = 3.0

    whatsapp_enabled: bool = False
    meta_graph_api_version: str = ""
    meta_whatsapp_phone_number_id: str = ""
    meta_whatsapp_access_token: SecretStr | None = None
    meta_webhook_verify_token: SecretStr | None = None
    meta_app_secret: SecretStr | None = None
    whatsapp_send_timeout_seconds: float = 10.0

    telegram_enabled: bool = False
    telegram_bot_token: SecretStr | None = None
    telegram_tenant_id: str = ""
    telegram_allowed_user_ids: list[int] = []
    telegram_poll_timeout_seconds: int = 30
    telegram_send_timeout_seconds: float = 10.0
    telegram_faq_file: str = ""

    redis_url: SecretStr | None = None
    message_stream_key: str = "ai:messages"
    message_consumer_group: str = "ai-workers"
    message_max_attempts: int = 3
    message_dedup_ttl_seconds: int = 604800
    checkpoint_database_url: SecretStr | None = None

    @field_validator("telegram_allowed_user_ids", mode="before")
    @classmethod
    def reject_non_integer_telegram_user_ids(cls, value: object) -> object:
        """Reject booleans before Pydantic can coerce them to integer IDs."""
        if not isinstance(value, list) or any(
            isinstance(item, bool) or not isinstance(item, int) for item in value
        ):
            raise ValueError("Telegram allowed user IDs must be integers.")
        return value

    def validate_ai_runtime(self) -> None:
        """Validate only when an AI runtime is deliberately being constructed."""
        if self.ai_provider.strip().lower() != "openrouter":
            raise ValueError("AI runtime currently supports only the openrouter provider.")
        if not self.ai_model.strip():
            raise ValueError("AI_MODEL must be nonblank when constructing the AI runtime.")
        if (
            self.openrouter_api_key is None
            or not self.openrouter_api_key.get_secret_value().strip()
        ):
            raise ValueError("OPENROUTER_API_KEY is required when constructing the AI runtime.")
        if not 1 <= self.ai_request_timeout_seconds <= 300:
            raise ValueError("AI request timeout must be between 1 and 300 seconds.")
        if not 2 <= self.ai_max_graph_steps <= 32:
            raise ValueError("AI graph steps must be between 2 and 32.")
        if not 64 <= self.ai_max_output_tokens <= 1024:
            raise ValueError("AI max output tokens must be between 64 and 1024.")
        if not 0.0 <= self.ai_temperature <= 1.0:
            raise ValueError("AI temperature must be between 0.0 and 1.0.")

    def validate_telegram_runtime(self) -> None:
        """Validate the optional local Telegram adapter only when it is enabled."""
        if not self.telegram_enabled:
            return
        if (
            self.telegram_bot_token is None
            or not self.telegram_bot_token.get_secret_value().strip()
        ):
            raise ValueError("Telegram bot token is required when Telegram is enabled.")
        tenant_id = self.telegram_tenant_id.strip()
        if not tenant_id or len(tenant_id) > 128:
            raise ValueError("Telegram tenant ID must be nonblank and at most 128 characters.")
        allowed_ids = self.telegram_allowed_user_ids
        if not allowed_ids or len(allowed_ids) != len(set(allowed_ids)):
            raise ValueError("Telegram allowed user IDs must be a nonempty unique list.")
        if any(isinstance(user_id, bool) or user_id <= 0 for user_id in allowed_ids):
            raise ValueError("Telegram allowed user IDs must contain positive integers only.")
        if not 1 <= self.telegram_poll_timeout_seconds <= 50:
            raise ValueError("Telegram poll timeout must be between 1 and 50 seconds.")
        if not 1 <= self.telegram_send_timeout_seconds <= 60:
            raise ValueError("Telegram send timeout must be between 1 and 60 seconds.")
        if self.chat_max_message_chars < 1 or self.chat_max_message_chars > 4000:
            raise ValueError("Chat max message chars must be between 1 and 4000 for Telegram.")


def load_settings(env_file: Path | None = None) -> Settings:
    """Load environment, optionally from an explicit dotenv file, without discovery."""
    try:
        return Settings(_env_file=env_file)
    except (SettingsError, ValidationError):
        raise RuntimeError("Invalid service configuration.") from None
