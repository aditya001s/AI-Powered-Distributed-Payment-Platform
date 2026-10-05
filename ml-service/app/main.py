"""
The HTTP surface: a thin adapter over :mod:`app.scoring`.

Everything numerically interesting lives behind this module. What is here is transport,
authentication, health and observability — deliberately, so that the behaviour which decides
whether a payment is blocked can be tested without a server.

Four decisions worth reading:

**The model is loaded and warmed at startup, never on the first request.** scikit-learn's
first ``predict_proba`` pays roughly 1.4 seconds of thread-pool and BLAS initialisation, and
measured here it did. Left to the first real request, that cost lands on a customer's payment
and blows the fraud latency budget on every cold pod — worst during a rolling deploy, when
every pod is cold. So the lifespan scores a synthetic row before the service reports ready.

**No model means not ready, not "score anyway".** The artifact is baked into the image by
``python -m app.train``; if it is missing, something is wrong with the build and the honest
response is to fail readiness so the pod never receives traffic. Training on boot instead
would let two replicas serve different models, so the same payment would score differently
depending on which one answered, and a disputed decision could not be reproduced.

**Errors use the platform's shape.** ``ApiError`` field for field, so fraud-service parses a
failure from this service with the same code it uses for every other service.

**Correlation IDs are propagated.** ``X-Correlation-Id`` is read from the request and echoed
on the response, so a score can be traced back to the payment that caused it across the
gateway, Kafka and four Java services.
"""

from __future__ import annotations

import hmac
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, AsyncIterator

from fastapi import Depends, FastAPI, Header, Request, Response, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse

from . import __version__
from .config import settings
from .metrics import METRICS
from .model import load_model
from .schemas import (
    ContributionView,
    ErrorResponse,
    HealthResponse,
    ModelInfoResponse,
    ScoreRequest,
    ScoreResponse,
)
from .scoring import ScoringEngine, feature_contract

logger = logging.getLogger("aegis.ml")

CORRELATION_HEADER = "X-Correlation-Id"
API_KEY_HEADER = "X-API-Key"

#: A synthetic row used to warm the model at startup. Its score is discarded; only the fact
#: that the first expensive call has already happened matters.
_WARMUP_REQUEST: dict[str, Any] = {
    "amount_minor": 150_000,
    "payment_type": "P2P_TRANSFER",
    "hour_of_day": 12,
    "day_of_week": 3,
    "account_age_days": 365,
    "prior_payment_count": 50,
    "prior_chargeback_count": 0,
    "avg_amount_minor": 140_000,
    "max_amount_minor": 400_000,
    "payment_count_1h": 0,
    "payment_count_24h": 1,
    "amount_sum_24h_minor": 140_000,
    "failed_payment_count_24h": 0,
    "seconds_since_last_payment": 86_400,
    "is_new_device": False,
    "distinct_devices_30d": 1,
    "is_new_country": False,
    "distinct_countries_30d": 1,
    "country_code": "IN",
    "distance_from_last_km": 5.0,
    "has_geo": True,
    "is_new_payee": False,
    "payee_merchant_category": "5411",
}


class ServiceState:
    """
    Holds the loaded engine.

    A tiny mutable holder rather than a module global, so a test can construct an app with a
    stub engine and so the "no model" path is reachable without deleting files.
    """

    def __init__(self) -> None:
        self.engine: ScoringEngine | None = None
        self.load_error: str | None = None


STATE = ServiceState()


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _error(request: Request, code: str, message: str, http_status: int) -> JSONResponse:
    """Builds the platform's error body."""
    correlation_id = request.headers.get(CORRELATION_HEADER)
    body = ErrorResponse(
        timestamp=_now(),
        status=http_status,
        code=code,
        message=message,
        path=request.url.path,
        correlation_id=correlation_id,
    )
    response = JSONResponse(status_code=http_status, content=body.model_dump(by_alias=True))
    if correlation_id:
        response.headers[CORRELATION_HEADER] = correlation_id
    return response


def load_engine() -> None:
    """
    Loads and warms the model, recording why if it cannot.

    Never raises. A missing or mismatched artifact must surface as a failed readiness probe
    with a readable reason, not as a stack trace during startup that leaves the container
    restarting in a loop with the cause buried in a previous log stream.
    """
    config = settings()
    try:
        model = load_model(config.artifact_path)
        engine = ScoringEngine(model)

        started = time.perf_counter()
        engine.score(_WARMUP_REQUEST)
        warmup_ms = (time.perf_counter() - started) * 1000.0

        STATE.engine = engine
        STATE.load_error = None
        METRICS.set_model(model.version, model.trained_at)

        logger.info(
            "Loaded fraud model %s (trained %s), warmed in %.0fms. "
            "Held-out ROC AUC %.4f, calibration error %.5f. SYNTHETIC training data.",
            model.version, model.trained_at, warmup_ms,
            model.metrics.roc_auc, model.metrics.expected_calibration_error,
        )
    except Exception as exc:  # noqa: BLE001 - readiness must report any cause, not just some
        STATE.engine = None
        STATE.load_error = str(exc)
        logger.error("No usable fraud model, so this instance will not become ready: %s", exc)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)-5s [aegis-ml] %(message)s",
    )
    # Fail fast on a missing secret rather than per-request, matching the Java services:
    # an unauthenticated scoring endpoint lets anyone map the model's decision boundary.
    settings().require_api_key()
    load_engine()
    yield


app = FastAPI(
    title="AegisPay ML Fraud Scoring",
    version=__version__,
    lifespan=lifespan,
    description=(
        "Calibrated fraud probability, a 0-100 risk score and a per-feature explanation.\n\n"
        "**Measurement only.** This service returns no risk level and no decision: bucketing "
        "a probability and choosing ALLOW / REVIEW / BLOCK requires the deterministic rules "
        "as well as the model, and only fraud-service has both.\n\n"
        "**The model is trained on SYNTHETIC data** with an imposed causal structure. The "
        "metrics at `GET /model` measure recovery of that structure and are not a claim "
        "about real-world fraud detection performance."
    ),
)


async def require_api_key(
    request: Request,
    x_api_key: str | None = Header(default=None, alias=API_KEY_HEADER),
) -> None:
    """
    Checks the shared secret in constant time.

    ``compare_digest`` rather than ``==`` because a plain comparison returns as soon as two
    bytes differ, and the timing difference is enough to recover a secret one character at a
    time given enough requests.

    Skipped entirely when no key is configured, which :meth:`Settings.require_api_key` only
    permits in local development.
    """
    expected = settings().api_key
    if not expected:
        return
    if not x_api_key or not hmac.compare_digest(x_api_key, expected):
        METRICS.record_request("unauthorised")
        # Deliberately vague: confirming that the header was present but wrong tells an
        # attacker their guess had the right shape.
        raise _Unauthorised()


class _Unauthorised(Exception):
    """Raised by the API-key dependency; rendered by the handler below."""


@app.exception_handler(_Unauthorised)
async def _handle_unauthorised(request: Request, _: _Unauthorised) -> JSONResponse:
    return _error(request, "UNAUTHORIZED", "A valid X-API-Key header is required.",
                  status.HTTP_401_UNAUTHORIZED)


@app.exception_handler(RequestValidationError)
async def _handle_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
    """
    Renders a validation failure in the platform's shape.

    The offending field names are included because the caller is another service, not an end
    user: "extra fields not permitted: amountMinorr" is exactly what a developer needs, and
    there is no customer to leak anything to.
    """
    problems = "; ".join(
        f"{'.'.join(str(part) for part in error['loc'][1:]) or 'body'}: {error['msg']}"
        for error in exc.errors()[:8]
    )
    METRICS.record_request("invalid_request")
    # UNPROCESSABLE_CONTENT, not the deprecated UNPROCESSABLE_ENTITY: the same rename RFC 9110
    # prompted, and the same one Spring Boot 4 made on the Java side.
    return _error(request, "VALIDATION_FAILED", f"Invalid scoring request. {problems}",
                  status.HTTP_422_UNPROCESSABLE_CONTENT)


@app.post(
    "/score",
    response_model=ScoreResponse,
    summary="Score one payment",
    responses={
        401: {"model": ErrorResponse, "description": "Missing or wrong API key"},
        422: {"model": ErrorResponse, "description": "Malformed request"},
        503: {"model": ErrorResponse, "description": "No model loaded; this instance is not ready"},
    },
    dependencies=[Depends(require_api_key)],
)
async def score(request: ScoreRequest, http_request: Request, response: Response) -> Any:
    """
    Returns the calibrated fraud probability, the same value as a 0-100 score, and the
    features that moved it.

    Deterministic: the same request always produces the same score for a given model version,
    which is what makes the returned feature snapshot sufficient to reproduce a disputed
    decision months later.
    """
    engine = STATE.engine
    if engine is None:
        METRICS.record_request("unavailable")
        return _error(
            http_request,
            "MODEL_UNAVAILABLE",
            f"No fraud model is loaded, so this instance cannot score. {STATE.load_error or ''}"
            .strip(),
            status.HTTP_503_SERVICE_UNAVAILABLE,
        )

    started = time.perf_counter()
    try:
        result = engine.score(request.to_features())
    except Exception as exc:  # noqa: BLE001 - a scoring failure must not leak a stack trace
        METRICS.record_request("error", time.perf_counter() - started)
        logger.exception("Scoring failed for payment %s", request.payment_id)
        return _error(http_request, "SCORING_FAILED",
                      f"The model could not score this payment: {exc}",
                      status.HTTP_500_INTERNAL_SERVER_ERROR)

    METRICS.record_request("success", time.perf_counter() - started)
    METRICS.record_score(result.risk_score)

    correlation_id = http_request.headers.get(CORRELATION_HEADER)
    if correlation_id:
        response.headers[CORRELATION_HEADER] = correlation_id

    return ScoreResponse(
        fraud_probability=result.fraud_probability,
        risk_score=result.risk_score,
        model_version=result.model_version,
        features=result.features,
        contributions=[
            ContributionView(
                feature=item.feature,
                description=item.description,
                value=item.value,
                impact_points=round(item.impact_points, 3),
                direction=item.direction,
            )
            for item in result.contributions
        ],
        scoring_latency_ms=result.scoring_latency_ms,
    )


@app.get(
    "/model",
    response_model=ModelInfoResponse,
    summary="Model provenance, metrics and the feature contract",
    responses={503: {"model": ErrorResponse}},
    dependencies=[Depends(require_api_key)],
)
async def model_info(http_request: Request) -> Any:
    """
    Everything needed to audit a decision: the build, the metrics it was accepted on, and the
    exact feature list.

    The feature contract is published here so fraud-service's request builder and this
    service cannot drift apart unnoticed — a mismatch becomes a diff between two documents
    rather than a score that quietly looks wrong.
    """
    engine = STATE.engine
    if engine is None:
        return _error(http_request, "MODEL_UNAVAILABLE",
                      f"No fraud model is loaded. {STATE.load_error or ''}".strip(),
                      status.HTTP_503_SERVICE_UNAVAILABLE)

    metadata = engine.model.to_metadata()
    return ModelInfoResponse(
        model_version=metadata["modelVersion"],
        trained_at=metadata["trainedAt"],
        algorithm=metadata["algorithm"],
        calibration=metadata["calibration"],
        feature_count=metadata["featureCount"],
        hyperparameters=metadata["hyperparameters"],
        metrics=metadata["metrics"],
        provenance=metadata["provenance"],
        data_caveat=metadata["dataCaveat"],
        feature_contract=feature_contract(),
    )


@app.get("/health", response_model=HealthResponse, summary="Liveness")
async def health() -> HealthResponse:
    """
    Whether the process is alive. Deliberately independent of the model.

    A liveness probe that failed on a missing model would restart the container in a loop,
    destroying the logs that explain why the model is missing. That is the same reasoning the
    Java services apply to excluding dependencies from liveness.
    """
    engine = STATE.engine
    return HealthResponse(
        status="UP",
        model_loaded=engine is not None,
        model_version=engine.model_version if engine else None,
    )


@app.get(
    "/health/readiness",
    response_model=HealthResponse,
    summary="Readiness",
    responses={503: {"model": ErrorResponse}},
)
async def readiness(http_request: Request) -> Any:
    """
    Whether this instance can actually score. Without a model it must receive no traffic.
    """
    engine = STATE.engine
    if engine is None:
        return _error(http_request, "MODEL_UNAVAILABLE",
                      f"No fraud model is loaded. {STATE.load_error or ''}".strip(),
                      status.HTTP_503_SERVICE_UNAVAILABLE)
    return HealthResponse(status="UP", model_loaded=True, model_version=engine.model_version)


@app.get(
    "/metrics",
    response_class=PlainTextResponse,
    summary="Prometheus metrics",
    description="Unauthenticated, like every other service's scrape endpoint: it exposes "
                "counters and a score histogram, never a payment or a feature value.",
)
async def metrics() -> PlainTextResponse:
    return PlainTextResponse(
        METRICS.render(),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )
