"""Local launcher for the AI Service runtime."""

import uvicorn

from app.application import app
from app.observability import setup_logging

__all__ = ["app"]


if __name__ == "__main__":
    setup_logging()
    # log_config=None keeps uvicorn's own loggers propagating to the root handlers.
    uvicorn.run(
        "app.application:app",
        host="127.0.0.1",
        port=8001,
        lifespan="on",
        log_config=None,
    )
