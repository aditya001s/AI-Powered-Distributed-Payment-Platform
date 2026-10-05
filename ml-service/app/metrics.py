"""
Prometheus metrics, in the text exposition format.

Hand-rolled rather than pulled from ``prometheus_client``, because the format is a dozen
lines of text and this keeps the image's dependency surface to FastAPI plus the scientific
stack. If this grew a summary, a registry or multiprocess support the calculation would
change; it has not.

Metric names are prefixed ``aegis_ml_`` to sit alongside the Java services' ``aegis.*``
meters after Micrometer's dot-to-underscore conversion, so one Grafana query can span both.

**The score histogram is the one that matters operationally.** A fraud model degrades
silently: it keeps returning numbers, and nothing fails. What changes first is the *shape* of
the score distribution, so a shifted histogram is the earliest available warning that the
world has moved away from the training data.
"""

from __future__ import annotations

import threading
import time
from typing import Iterable

#: Latency buckets in seconds. Dense around 5-50ms because that is where scoring actually
#: lands, with a tail out to 2s to make a cold-start warm-up visible rather than clipped.
LATENCY_BUCKETS: tuple[float, ...] = (
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0,
)

#: Score buckets, cumulative. The platform's thresholds (45 and 80) are bucket edges on
#: purpose, so "how many payments crossed the blocking threshold" is a direct read rather
#: than an interpolation between two neighbouring buckets.
SCORE_BUCKETS: tuple[float, ...] = (1, 5, 10, 20, 30, 45, 60, 80, 95, 100)


class _Histogram:
    """A cumulative histogram, Prometheus-style."""

    def __init__(self, buckets: Iterable[float]) -> None:
        self._buckets = tuple(sorted(buckets))
        self._counts = [0] * len(self._buckets)
        self._total = 0
        self._sum = 0.0

    def observe(self, value: float) -> None:
        self._total += 1
        self._sum += value
        for index, edge in enumerate(self._buckets):
            if value <= edge:
                self._counts[index] += 1

    def render(self, name: str, help_text: str) -> list[str]:
        lines = [f"# HELP {name} {help_text}", f"# TYPE {name} histogram"]
        # Prometheus histogram buckets are cumulative, so each edge reports everything at or
        # below it. The counts above already accumulate that way.
        for edge, count in zip(self._buckets, self._counts):
            edge_label = f"{edge:g}"
            lines.append(f'{name}_bucket{{le="{edge_label}"}} {count}')
        lines.append(f'{name}_bucket{{le="+Inf"}} {self._total}')
        lines.append(f"{name}_sum {self._sum:.6f}")
        lines.append(f"{name}_count {self._total}")
        return lines


class Metrics:
    """
    Process-wide counters.

    Guarded by a lock because FastAPI runs synchronous endpoint functions on a worker thread
    pool, so two requests genuinely do increment these concurrently. An unsynchronised
    read-modify-write on a counter loses increments, which turns an error-rate alert into an
    under-report exactly when it is being relied on.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._started = time.time()
        self._requests: dict[str, int] = {}
        self._latency = _Histogram(LATENCY_BUCKETS)
        self._scores = _Histogram(SCORE_BUCKETS)
        self._model_version: str | None = None
        self._model_trained_at: str | None = None

    def set_model(self, version: str, trained_at: str) -> None:
        with self._lock:
            self._model_version = version
            self._model_trained_at = trained_at

    def record_request(self, outcome: str, duration_seconds: float | None = None) -> None:
        with self._lock:
            self._requests[outcome] = self._requests.get(outcome, 0) + 1
            if duration_seconds is not None:
                self._latency.observe(duration_seconds)

    def record_score(self, risk_score: int) -> None:
        with self._lock:
            self._scores.observe(float(risk_score))

    def render(self) -> str:
        with self._lock:
            lines: list[str] = []

            lines.append("# HELP aegis_ml_up Whether the scoring service is serving.")
            lines.append("# TYPE aegis_ml_up gauge")
            lines.append(f"aegis_ml_up {1 if self._model_version else 0}")

            lines.append("# HELP aegis_ml_uptime_seconds Seconds since process start.")
            lines.append("# TYPE aegis_ml_uptime_seconds gauge")
            lines.append(f"aegis_ml_uptime_seconds {time.time() - self._started:.3f}")

            if self._model_version:
                # An info-style gauge: the value is always 1 and the labels carry the
                # payload, which is how Prometheus models immutable build metadata.
                lines.append(
                    "# HELP aegis_ml_model_info Loaded model build. Always 1; read the labels."
                )
                lines.append("# TYPE aegis_ml_model_info gauge")
                lines.append(
                    f'aegis_ml_model_info{{version="{self._model_version}",'
                    f'trained_at="{self._model_trained_at}"}} 1'
                )

            lines.append("# HELP aegis_ml_score_requests_total Scoring requests by outcome.")
            lines.append("# TYPE aegis_ml_score_requests_total counter")
            if not self._requests:
                # Emit a zero series so a dashboard has something to plot before the first
                # request, rather than showing "no data" and looking like an outage.
                lines.append('aegis_ml_score_requests_total{outcome="success"} 0')
            for outcome, count in sorted(self._requests.items()):
                lines.append(f'aegis_ml_score_requests_total{{outcome="{outcome}"}} {count}')

            lines.extend(self._latency.render(
                "aegis_ml_score_latency_seconds", "Scoring latency, including the explanation."
            ))
            lines.extend(self._scores.render(
                "aegis_ml_risk_score",
                "Distribution of returned risk scores. A shifted shape is the earliest "
                "warning of model drift, because a degrading model keeps returning numbers.",
            ))
            return "\n".join(lines) + "\n"


#: The process-wide instance. A module-level singleton rather than app state, so the training
#: CLI and the tests can import it without constructing an application.
METRICS = Metrics()
