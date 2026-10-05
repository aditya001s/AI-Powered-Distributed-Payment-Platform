"""
The scoring engine: probability, 0-100 risk score, and a per-payment explanation.

Imports no web framework. Everything that determines whether a payment is flagged is
exercisable — and is exercised — without an HTTP server.

**What the 0-100 score is.** The calibrated fraud probability expressed in percentage
points: a score of 62 means the model puts this payment's chance of being fraudulent at
about 62%. It is deliberately *not* a rescaled "severity out of 100". A monotone stretch
would look friendlier on a dashboard and would destroy the one property the number needs:
that the platform's configured thresholds mean what they say. ``FRAUD_HIGH_RISK_THRESHOLD``
is 0.80 and a score of 80 is that same boundary, so retuning policy is a config change and
nothing has to be recalculated anywhere else.

The consequence is worth stating because it surprises people: against a base rate near 1.7%,
most legitimate traffic scores in the low single digits and a well-calibrated model reaches
80 rarely. That is the model being right about a rare event, not the model being
insensitive. Any transform that spread the distribution to fill 0-100 would be inventing
confidence that is not there.

**What this service does not do.** It does not return a risk level and it does not return a
decision. Bucketing a probability and choosing ALLOW / REVIEW / BLOCK requires the
deterministic rules as well as the model, and only fraud-service has both. Putting the
thresholds here too would mean two services had to be redeployed in step to retune one
number.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Mapping

import numpy as np

from .features import FEATURE_DESCRIPTIONS, FEATURE_NAMES, derive, snapshot, vectorise
from .model import FraudModel

#: Contributions returned by default. Enough to explain a decision to an analyst, few
#: enough that the important ones are not buried; the rest are near-zero anyway.
DEFAULT_TOP_CONTRIBUTIONS: int = 6

#: Contributions smaller than this many score points are dropped. Below a tenth of a point
#: the number is numerical noise in the calibrator, and presenting it as a reason a payment
#: was held would be misleading.
MIN_CONTRIBUTION_POINTS: float = 0.1


@dataclass(frozen=True)
class Contribution:
    """
    One feature's effect on this payment's score.

    ``impact_points`` is signed and measured in score points: the amount the score would
    *fall* if this feature's value were unknown. Positive means the feature pushed the score
    up (incriminating); negative means it pulled the score down (exculpating, and worth
    showing — "the device was already known" is exactly why a large payment was allowed).
    """

    feature: str
    description: str
    value: float | None
    impact_points: float

    @property
    def direction(self) -> str:
        return "increases" if self.impact_points > 0 else "decreases"

    def to_dict(self) -> dict[str, Any]:
        return {
            "feature": self.feature,
            "description": self.description,
            "value": self.value,
            "impactPoints": round(self.impact_points, 3),
            "direction": self.direction,
        }


@dataclass(frozen=True)
class ScoreResult:
    """One payment's score, with everything needed to reproduce and explain it."""

    fraud_probability: float
    risk_score: int
    model_version: str
    features: dict[str, float | None]
    contributions: list[Contribution]
    scoring_latency_ms: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "fraudProbability": round(self.fraud_probability, 6),
            "riskScore": self.risk_score,
            "modelVersion": self.model_version,
            "features": self.features,
            "contributions": [item.to_dict() for item in self.contributions],
            "scoringLatencyMs": self.scoring_latency_ms,
        }


def to_risk_score(probability: float) -> int:
    """
    The calibrated probability as an integer 0-100.

    Rounded rather than truncated so 0.799 does not read as 79 while 0.800 reads as 80; and
    clamped because a calibrator can emit a hair outside [0,1] on the boundary bins.
    """
    if not np.isfinite(probability):
        raise ValueError("probability must be finite")
    return int(round(min(1.0, max(0.0, probability)) * 100))


class ScoringEngine:
    """
    Scores payments with a loaded model.

    Stateless between calls: the same request always produces the same score, which is what
    makes a stored feature snapshot sufficient to reproduce a disputed decision months later.
    """

    def __init__(self, model: FraudModel) -> None:
        self._model = model

    @property
    def model(self) -> FraudModel:
        return self._model

    @property
    def model_version(self) -> str:
        return self._model.version

    def score(self, request: Mapping[str, Any],
              top_contributions: int = DEFAULT_TOP_CONTRIBUTIONS) -> ScoreResult:
        """
        Scores one payment and explains the result.

        The explanation costs one extra model call, not one per feature: the actual vector
        and all 28 single-feature ablations go into a single matrix and through a single
        ``predict_proba``. Looping would multiply the request's latency by the feature count,
        and this call sits on the payment's critical path.
        """
        started = time.perf_counter()

        values = derive(request)
        row = vectorise(values, self._model.feature_names)

        matrix = self._model.ablation_row(row[0])
        probabilities = self._model.predict_proba(matrix)

        actual = float(probabilities[0])
        contributions = self._contributions(values, actual, probabilities[1:], top_contributions)

        elapsed_ms = int(round((time.perf_counter() - started) * 1000))

        return ScoreResult(
            fraud_probability=min(1.0, max(0.0, actual)),
            risk_score=to_risk_score(actual),
            model_version=self._model.version,
            features=snapshot(values),
            contributions=contributions,
            scoring_latency_ms=elapsed_ms,
        )

    def _contributions(self, values: Mapping[str, float], actual: float,
                       ablated: np.ndarray, limit: int) -> list[Contribution]:
        """
        Ranks features by how much the score depends on knowing them.

        This is leave-one-feature-out ablation, and it is worth being precise about what it
        does and does not claim. It answers "how far would this score move if we did not
        know this one feature", measured against the model itself, exactly. It is **not**
        SHAP: the values are not guaranteed to sum to the prediction, and a pair of features
        that only matter together will each look individually weak. For the question an
        analyst actually asks about a held payment — what drove this — it is the right
        measure, and it needs no additional dependency.
        """
        stored = snapshot(values)
        candidates: list[Contribution] = []

        for index, name in enumerate(self._model.feature_names):
            impact_points = (actual - float(ablated[index])) * 100.0
            if abs(impact_points) < MIN_CONTRIBUTION_POINTS:
                continue
            candidates.append(
                Contribution(
                    feature=name,
                    description=FEATURE_DESCRIPTIONS.get(name, name),
                    value=stored.get(name),
                    impact_points=impact_points,
                )
            )

        # Largest absolute effect first, then by name so equal impacts order stably rather
        # than depending on dict iteration.
        candidates.sort(key=lambda item: (-abs(item.impact_points), item.feature))
        return candidates[: max(0, limit)]


def feature_contract() -> dict[str, Any]:
    """
    The feature contract, for the ``/model`` endpoint.

    Published so fraud-service's request builder and this service cannot drift apart
    unnoticed: a mismatch becomes visible by comparing two documents rather than by
    wondering why a score looks wrong.
    """
    return {
        "featureCount": len(FEATURE_NAMES),
        "features": [
            {"name": name, "description": FEATURE_DESCRIPTIONS.get(name, name)}
            for name in FEATURE_NAMES
        ],
    }
