"""Structured logging: JSON shape, correlation context, redaction and setup idempotency."""
import io
import json
import logging
import tempfile
import unittest
from pathlib import Path

from app.observability import get_log_context, log_context, setup_logging
from app.observability.context import ContextFilter
from app.observability.formatter import StructuredJsonFormatter, TextFormatter
from app.observability.redaction import RedactionFilter
from app.observability.setup import shutdown_logging
from app.observability.settings import LoggingSettings, load_logging_settings

FAKE_BOT_TOKEN = "123456789:" + "A" * 35


def _capture_logger(name: str, formatter: logging.Formatter) -> tuple[logging.Logger, io.StringIO]:
    """Logger wired like the root pipeline (context + redaction filters) writing to a buffer."""
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(formatter)
    handler.addFilter(ContextFilter())
    handler.addFilter(RedactionFilter())
    logger = logging.getLogger(name)
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    return logger, stream


class JsonFormatTests(unittest.TestCase):
    def test_line_has_canonical_keys_and_extra(self) -> None:
        logger, stream = _capture_logger("obs.json", StructuredJsonFormatter())
        logger.info("Reply ready", extra={"event": "reply_ready", "elapsed_ms": 12})
        payload = json.loads(stream.getvalue().strip())
        self.assertEqual(payload["level"], "INFO")
        self.assertEqual(payload["logger"], "obs.json")
        self.assertEqual(payload["message"], "Reply ready")
        self.assertEqual(payload["event"], "reply_ready")
        self.assertEqual(payload["elapsed_ms"], 12)
        self.assertRegex(payload["ts"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
        self.assertNotIn("levelname", payload)

    def test_text_formatter_appends_key_value_pairs(self) -> None:
        logger, stream = _capture_logger("obs.text", TextFormatter())
        with log_context(chat_id=5):
            logger.info("Hello", extra={"event": "greeting"})
        line = stream.getvalue().strip()
        self.assertIn("[INFO] obs.text: Hello", line)
        self.assertIn("event=greeting", line)
        self.assertIn("chat_id=5", line)


class ContextTests(unittest.TestCase):
    def test_fields_injected_and_restored(self) -> None:
        logger, stream = _capture_logger("obs.ctx", StructuredJsonFormatter())
        self.assertEqual(get_log_context(), {})
        with log_context(update_id=301, chat_id=71, user_id=None):
            logger.info("inside", extra={"event": "inside"})
            self.assertEqual(get_log_context(), {"update_id": "301", "chat_id": "71"})
        logger.info("outside", extra={"event": "outside"})
        inside, outside = (json.loads(line) for line in stream.getvalue().splitlines())
        self.assertEqual(inside["update_id"], "301")
        self.assertEqual(inside["chat_id"], "71")
        self.assertNotIn("user_id", inside)
        self.assertNotIn("update_id", outside)
        self.assertEqual(get_log_context(), {})

    def test_nested_contexts_merge_and_restore(self) -> None:
        with log_context(chat_id=1):
            with log_context(user_id=2, chat_id=3):
                self.assertEqual(get_log_context(), {"chat_id": "3", "user_id": "2"})
            self.assertEqual(get_log_context(), {"chat_id": "1"})

    def test_explicit_extra_wins_over_context(self) -> None:
        logger, stream = _capture_logger("obs.ctx.extra", StructuredJsonFormatter())
        with log_context(chat_id=1):
            logger.info("x", extra={"chat_id": 99})
        self.assertEqual(json.loads(stream.getvalue())["chat_id"], 99)


class RedactionTests(unittest.TestCase):
    def test_masks_token_and_long_number_but_not_id_fields(self) -> None:
        logger, stream = _capture_logger("obs.redact", StructuredJsonFormatter())
        logger.info(
            "token %s phone %s",
            FAKE_BOT_TOKEN,
            "6281234567890",
            extra={"chat_id": 6281234567, "note": "call 08123456789 now"},
        )
        payload = json.loads(stream.getvalue())
        self.assertNotIn(FAKE_BOT_TOKEN, stream.getvalue())
        self.assertNotIn("6281234567890", payload["message"])
        self.assertNotIn("08123456789", payload["note"])
        self.assertEqual(payload["chat_id"], 6281234567)

    def test_masks_bearer_and_api_key(self) -> None:
        logger, stream = _capture_logger("obs.redact2", StructuredJsonFormatter())
        logger.info("auth Bearer abc.def-123 key sk-or-v1-" + "b" * 24)
        message = json.loads(stream.getvalue())["message"]
        self.assertNotIn("abc.def-123", message)
        self.assertNotIn("sk-or-v1", message)

    def test_masks_exception_text(self) -> None:
        logger, stream = _capture_logger("obs.redact3", StructuredJsonFormatter())
        try:
            raise ValueError("bad " + FAKE_BOT_TOKEN)
        except ValueError:
            logger.error("failed", exc_info=True)
        self.assertNotIn(FAKE_BOT_TOKEN, stream.getvalue())
        self.assertIn("ValueError", json.loads(stream.getvalue())["exc_info"])

    def test_filter_never_raises_on_bad_args(self) -> None:
        record = logging.LogRecord("x", logging.INFO, __file__, 1, "%d", ("not-int",), None)
        self.assertTrue(RedactionFilter().filter(record))


class SetupTests(unittest.TestCase):
    def setUp(self) -> None:
        self.root = logging.getLogger()
        self.saved_handlers = list(self.root.handlers)
        self.saved_level = self.root.level
        shutdown_logging()

    def tearDown(self) -> None:
        shutdown_logging()
        self.root.handlers = self.saved_handlers
        self.root.setLevel(self.saved_level)

    def test_setup_is_idempotent_and_reconfigure_does_not_duplicate_handlers(self) -> None:
        settings = LoggingSettings()
        setup_logging(settings)
        count = len(self.root.handlers)
        setup_logging(settings)
        setup_logging(settings)
        self.assertEqual(len(self.root.handlers), count)
        self.assertEqual(count, len(self.saved_handlers) + 1)

        setup_logging(LoggingSettings(level="INFO"))
        count = len(self.root.handlers)
        setup_logging(LoggingSettings(level="DEBUG"))
        self.assertEqual(len(self.root.handlers), count)
        self.assertEqual(self.root.level, logging.DEBUG)

    def test_uvicorn_loggers_propagate_without_own_handlers(self) -> None:
        logging.getLogger("uvicorn.access").addHandler(logging.NullHandler())
        setup_logging(LoggingSettings())
        for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
            logger = logging.getLogger(name)
            self.assertEqual(logger.handlers, [])
            self.assertTrue(logger.propagate)

    def test_file_handler_writes_json_lines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            settings = LoggingSettings(file_enabled=True, log_dir=Path(tmp) / "logs")
            setup_logging(settings)
            with log_context(chat_id=7):
                logging.getLogger("obs.file").info("to disk", extra={"event": "to_disk"})
            shutdown_logging()  # stops the listener, flushing the queue
            lines = (Path(tmp) / "logs" / "ai-services.log").read_text(encoding="utf-8")
            payload = json.loads(lines.splitlines()[-1])
            self.assertEqual(payload["event"], "to_disk")
            self.assertEqual(payload["chat_id"], "7")


class SettingsTests(unittest.TestCase):
    def test_settings_parsing(self) -> None:
        with self.subTest("invalid level falls back to info"):
            self.assertEqual(load_logging_settings({"LOG_LEVEL": "loud"}).level, "INFO")
            self.assertEqual(load_logging_settings({"LOG_LEVEL": "debug"}).level, "DEBUG")
        with self.subTest("defaults and bad numbers"):
            settings = load_logging_settings(
                {"LOG_MAX_BYTES": "abc", "LOG_BACKUP_COUNT": "-1", "LOG_FORMAT": "xml"}
            )
            self.assertEqual(settings.log_format, "json")
            self.assertFalse(settings.file_enabled)
            self.assertEqual(settings.log_dir, Path("logs"))
            self.assertEqual(settings.max_bytes, 10485760)
            self.assertEqual(settings.backup_count, 5)
        with self.subTest("file enabled flag"):
            self.assertTrue(load_logging_settings({"LOG_FILE_ENABLED": "true"}).file_enabled)
            self.assertFalse(load_logging_settings({"LOG_FILE_ENABLED": "nope"}).file_enabled)


if __name__ == "__main__":
    unittest.main()
