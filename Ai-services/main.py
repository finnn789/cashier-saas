"""Local launcher for the AI Service runtime."""

import logging
import uvicorn

from app.application import app

__all__ = ["app"]


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    uvicorn.run("app.application:app", host="127.0.0.1", port=8001, lifespan="on")
