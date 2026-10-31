"""Local launcher for the AI Service runtime."""

import uvicorn

from app.application import app

__all__ = ["app"]


if __name__ == "__main__":
    uvicorn.run("app.application:app", host="127.0.0.1", port=8001, lifespan="on")
