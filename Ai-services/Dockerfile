# syntax=docker/dockerfile:1
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 UV_COMPILE_BYTECODE=0 UV_LINK_MODE=copy
RUN pip install --no-cache-dir uv
WORKDIR /app

# Dependencies first (cached layer), straight from uv.lock.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev

COPY . .
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app
USER app
ENV PATH="/app/.venv/bin:$PATH"
EXPOSE 8001

# Apply Alembic migrations to DATABASE_URL (cashier_ai), then serve FastAPI + Telegram long polling.
# main.py binds 127.0.0.1, so run uvicorn directly to listen on all interfaces inside the container.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.application:app --host 0.0.0.0 --port 8001"]
