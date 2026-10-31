"""Offline configuration tests; dotenv is always mocked."""
import os
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import UUID

import app.config as config
from app.ai.config import load_ai_settings
from app.channels.telegram.config import load_telegram_settings
from app.core.database import load_database_settings


DUMMY_TENANT_ID = UUID("12345678-1234-4234-8234-123456789abc")


class ConfigurationTests(unittest.TestCase):
    def environment(self) -> dict[str, str]:
        return {
            "TELEGRAM_BOT_TOKEN": " dummy-token ",
            "TELEGRAM_ALLOWED_USER_IDS": "7, 8,7",
            "AI_TENANT_ID": f" {DUMMY_TENANT_ID} ",
            "OPENROUTER_API_KEY": " dummy-key ",
            "AI_MODEL": " dummy-model ",
            "DATABASE_URL": "postgresql://unit:dummy-password@localhost/unit",
        }

    def test_process_environment_and_dotenv_loaded_once(self) -> None:
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(
            config, "_dotenv_loaded", False
        ), patch.object(config, "load_dotenv") as loader:
            first = config.load_settings()
            os.environ["AI_MODEL"] = "changed-model"
            second = config.load_settings()
        loader.assert_called_once_with(
            Path(config.__file__).resolve().parents[1] / ".env", override=False
        )
        self.assertEqual(first.ai.ai_model, "dummy-model")
        self.assertEqual(second.ai.ai_model, "changed-model")
        self.assertEqual(first.telegram.allowed_user_ids, frozenset({7, 8}))
        self.assertEqual(first.telegram.tenant_id, DUMMY_TENANT_ID)
        self.assertEqual(first.telegram.token, "dummy-token")
        self.assertEqual(first.ai.openrouter_key, "dummy-key")
        self.assertTrue(first.database.url.startswith("postgresql+asyncpg://"))

    def test_missing_and_malformed_tenant_id_have_safe_errors(self) -> None:
        for tenant_id in (None, "", "   ", "dummy-secret", "12345678-1234-4234-8234-invalid-secret"):
            with self.subTest(tenant_id=tenant_id), patch.dict(
                os.environ,
                {} if tenant_id is None else {"AI_TENANT_ID": tenant_id},
                clear=True,
            ):
                with self.assertRaises(RuntimeError) as caught:
                    load_telegram_settings()
                self.assertIn("AI_TENANT_ID", str(caught.exception))
                self.assertNotIn("dummy-secret", str(caught.exception))
                self.assertNotIn("invalid-secret", str(caught.exception))

    def test_invalid_allowlists_do_not_echo_input_and_are_checked_first(self) -> None:
        for allowed in ("0", "-7", "7,dummy-secret", "1.5"):
            with self.subTest(allowed=allowed), patch.dict(os.environ, {
                "TELEGRAM_ALLOWED_USER_IDS": allowed
            }, clear=True):
                with self.assertRaises(RuntimeError) as caught:
                    load_telegram_settings()
                self.assertEqual(str(caught.exception),
                    "TELEGRAM_ALLOWED_USER_IDS harus berisi ID positif dipisahkan koma.")

    def test_empty_allowlist_and_missing_ai_credentials(self) -> None:
        with patch.dict(os.environ, {"AI_TENANT_ID": str(DUMMY_TENANT_ID),
                                    "TELEGRAM_ALLOWED_USER_IDS": " , "}, clear=True):
            self.assertEqual(load_telegram_settings().allowed_user_ids, frozenset())
            self.assertEqual(load_telegram_settings().tenant_id, DUMMY_TENANT_ID)
            self.assertEqual(load_ai_settings().openrouter_key, "")
            self.assertEqual(load_ai_settings().ai_model, "")

    def test_settings_repr_and_invalid_database_errors_hide_secrets(self) -> None:
        with patch.dict(os.environ, self.environment(), clear=True), patch.object(
            config, "load_dotenv"
        ), patch.object(config, "_dotenv_loaded", False):
            rendered = repr(config.load_settings())
        for secret in ("dummy-token", "dummy-key", "dummy-password"):
            self.assertNotIn(secret, rendered)
        for url in ("dummy-secret", "sqlite:///dummy-secret", "postgresql://u:dummy-secret@host:bad/db"):
            with self.subTest(url=url), patch.dict(os.environ, {"DATABASE_URL": url}, clear=True):
                with self.assertRaises(RuntimeError) as caught:
                    load_database_settings()
                self.assertEqual(str(caught.exception),
                    "DATABASE_URL harus berupa URL PostgreSQL AI yang valid.")
