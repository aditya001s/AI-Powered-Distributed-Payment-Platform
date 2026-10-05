"""
The HTTP contract.

**Field names are camelCase on the wire and snake_case in Python.** fraud-service is a
Spring application serialising Java records, so the wire format is camelCase; this module
declares an alias generator rather than asking either side to speak the other's convention.
Getting that wrong is a quiet failure: an unrecognised field would be ignored, the feature
would arrive as ``None``, and the score would be computed from a partial vector without any
error being raised. Hence ``extra="forbid"`` — an unknown or misspelled field is rejected
loudly instead.

**Almost everything is optional, and that is deliberate.** A brand-new account has no
average amount, an API-channel payment has no device, and a customer who denied location
permission has no coordinates. Those are real states, and :mod:`app.features` treats absence
as its own signal. The only genuinely required field is the amount, because a payment
without one is not a payment.

**The response carries no decision.** It reports what the model measured — a calibrated
probability, the same number as a 0-100 score, and which features moved it. Turning that
into ALLOW / REVIEW / BLOCK needs the deterministic rules too, and only fraud-service has
both.
"""

from __future__ import annotations

from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

#: Shared by every model here: camelCase aliases, populatable by either name, and no
#: tolerance for unknown fields.
#:
#: Note the explicit aliases on every field with a digit in its name, below. ``to_camel``
#: title-cases each underscore-separated part, so ``payment_count_1h`` becomes
#: ``paymentCount1H`` — while Jackson, serialising a Java field called ``paymentCount1h``,
#: sends ``paymentCount1h``. The two disagree on one character. ``extra="forbid"`` turns that
#: into a loud 422 rather than six silently-absent features, but it must not happen at all, so
#: those aliases are written out by hand and pinned by a test.
_WIRE = ConfigDict(
    alias_generator=to_camel,
    populate_by_name=True,
    extra="forbid",
)

#: Amounts are integer minor units throughout the platform, never floats. The ceiling is a
#: sanity bound rather than a business limit: a value this large is a unit error somewhere
#: upstream, and silently scoring it would put the row far outside anything trained on.
MinorUnits = Annotated[int, Field(ge=0, le=10**15)]
Count = Annotated[int, Field(ge=0, le=10**9)]


class ScoreRequest(BaseModel):
    """One payment to score, with the behavioural aggregates fraud-service computed."""

    model_config = _WIRE

    # ---- identity, for correlation only. Never features: a model that keyed off a payment
    # id would be memorising rather than generalising.
    payment_id: str | None = Field(default=None, max_length=48)
    transaction_id: str | None = Field(default=None, max_length=48)

    # ---- the transaction ----
    amount_minor: MinorUnits = Field(description="Payment amount in minor units")
    currency: str | None = Field(default=None, min_length=3, max_length=3)
    payment_type: str | None = Field(default=None, max_length=32)
    hour_of_day: Annotated[int, Field(ge=0, le=23)] | None = None
    day_of_week: Annotated[int, Field(ge=1, le=7)] | None = Field(
        default=None, description="ISO-8601: Monday is 1, Sunday is 7"
    )

    # ---- the account ----
    account_age_days: Count | None = None
    prior_payment_count: Count | None = None
    prior_chargeback_count: Count | None = None

    # ---- how this account normally behaves ----
    avg_amount_minor: MinorUnits | None = Field(
        default=None, description="Null for an account with no history yet"
    )
    max_amount_minor: MinorUnits | None = None

    # ---- velocity. Every window is PRIOR to this payment and excludes it. ----
    # Explicit aliases: see the note on _WIRE about to_camel and digits.
    payment_count_1h: Count | None = Field(default=None, alias="paymentCount1h")
    payment_count_24h: Count | None = Field(default=None, alias="paymentCount24h")
    amount_sum_24h_minor: MinorUnits | None = Field(
        default=None,
        alias="amountSum24hMinor",
        description="Prior total in the last 24 hours, excluding this payment. May "
                    "legitimately be smaller than amountMinor.",
    )
    failed_payment_count_24h: Count | None = Field(
        default=None, alias="failedPaymentCount24h"
    )
    seconds_since_last_payment: Count | None = None

    # ---- device and location ----
    is_new_device: bool | None = Field(
        default=None, description="Null when no device context was available at all"
    )
    distinct_devices_30d: Count | None = Field(default=None, alias="distinctDevices30d")
    is_new_country: bool | None = None
    distinct_countries_30d: Count | None = Field(default=None, alias="distinctCountries30d")
    country_code: str | None = Field(default=None, max_length=2)
    distance_from_last_km: Annotated[float, Field(ge=0.0, le=25_000.0)] | None = None
    has_geo: bool = False

    # ---- counterparty ----
    is_new_payee: bool | None = None
    payee_merchant_category: str | None = Field(default=None, max_length=8)

    def to_features(self) -> dict[str, Any]:
        """
        The raw mapping :func:`app.features.derive` consumes.

        ``by_alias=False`` so the keys are snake_case, which is what the feature code reads.
        """
        return self.model_dump(by_alias=False, exclude_none=False)


class ContributionView(BaseModel):
    """One feature's effect on this payment's score."""

    model_config = _WIRE

    feature: str
    description: str
    value: float | None = Field(description="The feature's value; null when unknown")
    impact_points: float = Field(
        description="Signed score points. Positive means this feature raised the score; "
                    "negative means it lowered it, which is worth showing — 'the device was "
                    "already known' is often why a large payment was allowed."
    )
    direction: str = Field(description="increases or decreases")


class ScoreResponse(BaseModel):
    """What the model measured. Not a decision."""

    model_config = _WIRE

    fraud_probability: float = Field(
        ge=0.0, le=1.0, description="Calibrated probability that this payment is fraudulent"
    )
    risk_score: int = Field(
        ge=0, le=100,
        description="The same number in percentage points. Deliberately not rescaled to "
                    "fill 0-100: the platform's thresholds are probabilities, so a score of "
                    "80 has to be the 0.80 boundary.",
    )
    model_version: str
    features: dict[str, float | None] = Field(
        description="The exact vector scored, retained so a disputed decision is "
                    "reproducible. Null means the value was genuinely unknown."
    )
    contributions: list[ContributionView]
    scoring_latency_ms: int


class ModelInfoResponse(BaseModel):
    """Model provenance, metrics and the feature contract."""

    model_config = _WIRE

    model_version: str
    trained_at: str
    algorithm: str
    calibration: str
    feature_count: int
    hyperparameters: dict[str, Any]
    metrics: dict[str, Any]
    provenance: dict[str, Any]
    data_caveat: str
    feature_contract: dict[str, Any]


class HealthResponse(BaseModel):
    """Liveness and readiness."""

    model_config = _WIRE

    status: str
    model_loaded: bool
    model_version: str | None = None


class ErrorResponse(BaseModel):
    """
    The platform's error shape.

    Matches ``com.aegispay.common.api.ApiError`` field for field so that a failure from this
    service is parsed by fraud-service's existing error handling rather than needing a
    Python-shaped special case.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    timestamp: str
    status: int
    code: str
    message: str
    path: str
    correlation_id: str | None = None
