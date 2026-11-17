# =============================================================================
# AegisPay ml-service — fraud scoring.
#
# Not built from infrastructure/docker/Dockerfile.service: that file is
# parameterised for JVM modules and shares nothing with a Python image beyond the
# non-root user. Forcing one file to cover both would leave a pile of
# conditionals, which is exactly the drift that file exists to prevent.
#
#   docker build -f ml-service/Dockerfile -t aegispay/ml-service ml-service
#
# Build context is ml-service/ alone. This service depends on no shared module,
# because it is a pure function of its request — which is also why it can be
# scaled without sharing any state.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: dependencies and training
#
# The model is trained HERE, at image build time, and the artifact is copied into
# the runtime stage. Two consequences, and the second is the important one:
#
#   * Startup is fast and cannot fail on a training error.
#   * Every replica serves a byte-identical model. Training on boot would let two
#     pods disagree about the same payment, so a score would depend on which pod
#     answered and a disputed decision could not be reproduced at all.
#
# The training seed is fixed (ML_RANDOM_SEED), so rebuilding this image from the
# same commit produces the same model and the same recorded model version.
# -----------------------------------------------------------------------------
FROM python:3.15-slim AS build

WORKDIR /build

# Dependency layer first, on its own, so editing application code does not
# reinstall scikit-learn and numpy. That is the difference between a ~10 second
# rebuild and a ~2 minute one.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Copy only the training import graph before producing the artifact. Serving-only
# FastAPI changes (routes, schemas, metrics) must not retrain an unchanged model.
# Docker still invalidates this layer whenever model behaviour, features, data
# generation, training configuration or the dependency lock changes.
COPY app/__init__.py app/config.py app/dataset.py app/features.py app/model.py app/train.py app/

# Training arguments come from the environment so a build can be retuned without
# editing this file. The defaults match .env.example.
ARG ML_TRAINING_ROWS=120000
ARG ML_FRAUD_RATE=0.017
ARG ML_RANDOM_SEED=42
ARG MODEL_NAME=fraud_hgb

ENV ML_TRAINING_ROWS=${ML_TRAINING_ROWS} \
    ML_FRAUD_RATE=${ML_FRAUD_RATE} \
    ML_RANDOM_SEED=${ML_RANDOM_SEED} \
    MODEL_NAME=${MODEL_NAME} \
    MODEL_DIR=/build/models

RUN python -m app.train

# -----------------------------------------------------------------------------
# Stage 2: runtime
#
# Reinstalls the dependencies rather than copying site-packages from the build
# stage, because scikit-learn ships compiled extensions and copying an
# environment between images is how a subtly broken one gets shipped.
# -----------------------------------------------------------------------------
FROM python:3.15-slim AS runtime

LABEL org.opencontainers.image.title="AegisPay ml-service" \
      org.opencontainers.image.description="Calibrated fraud probability, 0-100 risk score and per-feature explanation. Model trained on SYNTHETIC data." \
      org.opencontainers.image.vendor="AegisPay" \
      org.opencontainers.image.licenses="MIT"

# curl is needed by the compose healthcheck. Installed and the apt lists removed
# in the same layer, so the cache does not persist into the image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# ---- Non-root, with the same fixed UID as the JVM images ----
# A compromised process running as root has root in the container, which widens
# the path to a host escape considerably. The UID is fixed so ownership is
# predictable across rebuilds and on mounted volumes.
RUN groupadd --system --gid 10001 aegis \
 && useradd  --system --uid 10001 --gid aegis --home /app --shell /usr/sbin/nologin aegis

WORKDIR /app

COPY requirements.txt ./
# Test-only packages are stripped: pytest and httpx have no business in a
# production image, and every package present is attack surface.
RUN grep -viE '^(pytest|httpx)==' requirements.txt > runtime-requirements.txt \
 && pip install --no-cache-dir -r runtime-requirements.txt \
 && rm runtime-requirements.txt

COPY --chown=aegis:aegis app/ app/
COPY --from=build --chown=aegis:aegis /build/models/ /app/models/

USER aegis:aegis

EXPOSE 8000

ENV MODEL_DIR=/app/models \
    MODEL_NAME=fraud_hgb \
    SERVER_PORT=8000 \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

# Pin BLAS/OpenMP to one thread. Left unset, the process spawns a thread per core
# and they contend: measurably WORSE latency on a many-core host than a single
# thread, because each prediction is a handful of tiny matrix operations that
# parallelise badly. Set as its own instruction because a Dockerfile does not
# allow comments inside a line-continued ENV.
ENV OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1

# Liveness only, matching the JVM services: it deliberately does not check that a
# model is loaded. A liveness probe that failed on a missing model would restart
# the container in a loop and destroy the logs explaining why it is missing.
# Readiness is /health/readiness, which the orchestrator checks separately.
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:8000/health" || exit 1

# exec form so uvicorn is PID 1 and receives SIGTERM directly, which lets it
# drain in-flight requests. The shell form would make /bin/sh PID 1 and swallow
# the signal, dropping a request on every deploy.
#
# One worker per container: scale with replicas, not with in-process workers. Each
# worker loads its own ~MB model copy, and a single-worker container is the unit
# the orchestrator can actually schedule and reason about.
ENTRYPOINT ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", \
            "--workers", "1", "--no-access-log"]
