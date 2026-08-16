# Multi-stage: the build stage carries the toolchain, the runtime stage does not.
# The same image runs in dev, staging, and production. Only environment variables differ.
# A staging-specific image is a staging-specific bug.

FROM python:3.12-slim AS build

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /build

# Build-only toolchain. Present here, absent from the runtime layer.
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential \
 && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
COPY app ./app

RUN python -m venv /opt/venv \
 && /opt/venv/bin/pip install --upgrade pip==25.0 \
 && /opt/venv/bin/pip install .


FROM python:3.12-slim AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PATH="/opt/venv/bin:$PATH"

# Non-root. A regulated service that runs as root has already lost an argument it did not need
# to have.
RUN groupadd --gid 10001 samd \
 && useradd --uid 10001 --gid samd --no-create-home --shell /usr/sbin/nologin samd

WORKDIR /srv/app

COPY --from=build /opt/venv /opt/venv
COPY app ./app
COPY alembic ./alembic
COPY alembic.ini ./alembic.ini

USER samd

EXPOSE 8080

# No healthcheck directive here: the orchestrator owns liveness policy and probes GET /health
# itself. Baking one in duplicates that decision in a place nobody looks.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080"]
