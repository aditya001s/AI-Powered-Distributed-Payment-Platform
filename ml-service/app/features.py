"""
The feature contract.

This module is the single definition of what the model sees, shared by training and by
serving. Two properties matter enough to state explicitly:

**The order of ``FEATURE_NAMES`` is part of the model contract.** A gradient-boosted tree
indexes columns positionally, so reordering this list silently changes the meaning of
every split in a model trained against the old order. It does not fail; it just returns
wrong answers. Hence the assertion in :func:`vectorise` and the name list stored inside
the artifact.

**Absence is a signal, not a gap to be filled.** An API-channel payment genuinely has no
device fingerprint, and a customer who denied location permission genuinely has no
coordinates. Imputing a median would tell the model "average device" when the truth is "no
device at all", which is one of the more predictive facts available.
:class:`~sklearn.ensemble.HistGradientBoostingClassifier` learns a split direction for
``NaN`` natively, so missing values are passed through as ``NaN`` rather than replaced.

**Nothing here is stateful.** Every behavioural aggregate — how many payments this account
made in the last day, how far it is from the last known location — is computed by
fraud-service and sent in the request. That keeps this service a pure function of its
input, which is what makes a stored feature snapshot enough to reproduce a score exactly,
and what lets replicas scale without sharing a read model.

**Every window aggregate is PRIOR to the payment being scored, and excludes it.** So
``payment_count_24h`` of 0 means "this is the first attempt today", and
``amount_sum_24h_minor`` may legitimately be smaller than ``amount_minor``. Leaving that
ambiguous is not a documentation problem, it is a correctness one: a caller that included
the current payment and a training set that did not would put every request slightly outside
the distribution the model learned, and the score would degrade with no error anywhere.
:func:`derive` additionally repairs the one invariant that must hold — a 24-hour window
contains the 1-hour window — rather than trusting each caller to get it right.
"""

from __future__ import annotations

from typing import Any, Mapping, Sequence

import numpy as np

#: Countries the platform sees enough confirmed fraud from to treat as a signal in their
#: own right. Deliberately short and deliberately configurable-looking rather than
#: configurable: a geography list that changes without review is a discrimination risk,
#: and it belongs in a reviewed release, not in an environment variable.
ELEVATED_RISK_COUNTRIES: frozenset[str] = frozenset({"XX", "ZZ", "A1", "A2"})

#: Merchant category codes with materially higher observed fraud rates. MCC 6011 is ATM
#: cash, 7995 gambling, 6051 quasi-cash including crypto on-ramps, 5967 direct marketing.
HIGH_RISK_MERCHANT_CATEGORIES: frozenset[str] = frozenset({"6011", "7995", "6051", "5967"})

#: Ordered feature vector. See the module docstring on why the order is load-bearing.
FEATURE_NAMES: tuple[str, ...] = (
    # ---- the transaction itself ----
    "amount_minor",
    "amount_log",
    "is_round_amount",
    "hour_of_day",
    "is_night",
    "is_weekend",
    "payment_type_is_merchant",
    "payment_type_is_withdrawal",
    # ---- who is paying ----
    "account_age_days",
    "prior_payment_count",
    "prior_chargeback_count",
    # ---- how they normally behave, and how this compares ----
    "avg_amount_minor",
    "amount_to_avg_ratio",
    "amount_over_historic_max",
    # ---- velocity ----
    "payment_count_1h",
    "payment_count_24h",
    "amount_sum_24h_minor",
    "failed_payment_count_24h",
    "seconds_since_last_payment",
    # ---- device and location ----
    "is_new_device",
    "distinct_devices_30d",
    "is_new_country",
    "distinct_countries_30d",
    "is_elevated_risk_country",
    "distance_from_last_km",
    "has_geo",
    # ---- counterparty ----
    "is_new_payee",
    "payee_is_high_risk_category",
)

FEATURE_COUNT: int = len(FEATURE_NAMES)

#: Human-readable descriptions, surfaced with the per-feature explanation so an analyst
#: reading a held payment does not have to guess what ``amount_to_avg_ratio`` means.
FEATURE_DESCRIPTIONS: Mapping[str, str] = {
    "amount_minor": "Payment amount in minor units",
    "amount_log": "Log-scaled amount, so a 10x jump matters as much at every size",
    "is_round_amount": "Amount is a round multiple of 1,000 major units",
    "hour_of_day": "Local hour the payment was initiated",
    "is_night": "Initiated between 00:00 and 05:00",
    "is_weekend": "Initiated on a Saturday or Sunday",
    "payment_type_is_merchant": "Payment to a merchant rather than a person",
    "payment_type_is_withdrawal": "Withdrawal out of the platform",
    "account_age_days": "Days since the account was opened",
    "prior_payment_count": "Successful payments this account has made before",
    "prior_chargeback_count": "Chargebacks previously raised against this account",
    "avg_amount_minor": "This account's mean historic payment amount",
    "amount_to_avg_ratio": "This amount divided by the account's mean amount",
    "amount_over_historic_max": "Amount exceeds anything this account has sent before",
    "payment_count_1h": "Prior payments attempted in the last hour",
    "payment_count_24h": "Prior payments attempted in the last 24 hours",
    "amount_sum_24h_minor": "Prior total attempted in the last 24 hours",
    "failed_payment_count_24h": "Failed or declined attempts in the last 24 hours",
    "seconds_since_last_payment": "Time since this account's previous attempt",
    "is_new_device": "Device has not been seen on this account before",
    "distinct_devices_30d": "Distinct devices used in the last 30 days",
    "is_new_country": "Country has not been seen on this account before",
    "distinct_countries_30d": "Distinct countries seen in the last 30 days",
    "is_elevated_risk_country": "Country is on the elevated-risk list",
    "distance_from_last_km": "Kilometres from the previous payment's location",
    "has_geo": "Location was available at all",
    "is_new_payee": "First payment this account has sent to this counterparty",
    "payee_is_high_risk_category": "Counterparty's merchant category is high risk",
}

#: Features whose absence is meaningful and must reach the model as ``NaN`` rather than
#: being defaulted. Everything else has a well-defined zero.
NULLABLE_FEATURES: frozenset[str] = frozenset(
    {
        "avg_amount_minor",
        "amount_to_avg_ratio",
        "seconds_since_last_payment",
        "distance_from_last_km",
        "hour_of_day",
        "is_night",
        "is_weekend",
    }
)


def _number(value: Any) -> float:
    """Coerces to float, mapping absent values to ``NaN`` rather than to zero."""
    if value is None:
        return float("nan")
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    try:
        result = float(value)
    except (TypeError, ValueError):
        return float("nan")
    # Infinity would poison a histogram bin edge and silently distort every split.
    if not np.isfinite(result):
        return float("nan")
    return result


def _flag(value: Any) -> float:
    """Coerces a tri-state boolean: true, false, or genuinely unknown."""
    if value is None:
        return float("nan")
    if isinstance(value, bool):
        return 1.0 if value else 0.0
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in {"true", "t", "yes", "y", "1"}:
            return 1.0
        if lowered in {"false", "f", "no", "n", "0"}:
            return 0.0
        return float("nan")
    return _number(value)


def _non_negative(value: Any) -> float:
    """
    A count that cannot be negative.

    A negative count means the caller computed an aggregate wrongly. Clamping is better
    than passing it through: a value outside anything seen in training lands in the
    outermost histogram bin and quietly drags the prediction with it.
    """
    number = _number(value)
    if np.isnan(number):
        return float("nan")
    return max(0.0, number)


def derive(raw: Mapping[str, Any]) -> dict[str, float]:
    """
    Turns one scoring request into the named feature values.

    Returns a dict rather than an array so the result can be stored as the payment's
    feature snapshot and read by a human during a dispute. :func:`vectorise` converts it
    to the positional form the model wants.
    """
    amount_minor = _non_negative(raw.get("amount_minor"))
    avg_amount = _non_negative(raw.get("avg_amount_minor"))
    max_amount = _non_negative(raw.get("max_amount_minor"))

    # Log-scaled, because fraud risk moves with the *order of magnitude* of an amount, not
    # with its absolute size: the difference between 100 and 1,000 matters far more than
    # between 100,000 and 100,900. log1p so a zero amount stays defined.
    amount_log = float("nan") if np.isnan(amount_minor) else float(np.log1p(amount_minor))

    # Ratio to the account's own norm. This is the single most useful transformation in
    # the set: it turns "is this a big payment" into "is this big *for this customer*",
    # which is the question that actually separates fraud from a wealthy customer.
    if np.isnan(amount_minor) or np.isnan(avg_amount) or avg_amount <= 0.0:
        amount_to_avg = float("nan")
    else:
        amount_to_avg = amount_minor / avg_amount

    if np.isnan(amount_minor) or np.isnan(max_amount) or max_amount <= 0.0:
        over_max = float("nan")
    else:
        over_max = 1.0 if amount_minor > max_amount else 0.0

    hour = _number(raw.get("hour_of_day"))
    if not np.isnan(hour):
        hour = float(int(hour) % 24)
    is_night = float("nan") if np.isnan(hour) else (1.0 if hour < 5.0 else 0.0)

    day_of_week = _number(raw.get("day_of_week"))
    if np.isnan(day_of_week):
        is_weekend = float("nan")
    else:
        # ISO-8601: Monday is 1, so 6 and 7 are the weekend.
        is_weekend = 1.0 if int(day_of_week) in (6, 7) else 0.0

    count_1h = _non_negative(raw.get("payment_count_1h"))
    count_24h = _non_negative(raw.get("payment_count_24h"))
    # A 24-hour window contains the 1-hour window, so the wider count cannot be the smaller
    # one. Repaired here rather than rejected: a miscomputed aggregate should not fail a
    # payment, but it must not be passed through either, because the pair
    # (count_1h=17, count_24h=2) describes a world that never appeared in training and the
    # model would extrapolate from it silently.
    if not np.isnan(count_1h) and not np.isnan(count_24h):
        count_24h = max(count_24h, count_1h)

    payment_type = str(raw.get("payment_type") or "").upper()
    country = str(raw.get("country_code") or "").upper()
    category = str(raw.get("payee_merchant_category") or "").strip()

    # A round amount is weakly associated with a human choosing a number rather than
    # paying an invoice. Only meaningful together with the other signals, which is exactly
    # what a tree ensemble is good at.
    if np.isnan(amount_minor):
        is_round = float("nan")
    else:
        is_round = 1.0 if amount_minor > 0 and amount_minor % 100_000 == 0 else 0.0

    return {
        "amount_minor": amount_minor,
        "amount_log": amount_log,
        "is_round_amount": is_round,
        "hour_of_day": hour,
        "is_night": is_night,
        "is_weekend": is_weekend,
        "payment_type_is_merchant": 1.0 if payment_type == "MERCHANT_PAYMENT" else 0.0,
        "payment_type_is_withdrawal": 1.0 if payment_type == "WALLET_WITHDRAWAL" else 0.0,
        "account_age_days": _non_negative(raw.get("account_age_days")),
        "prior_payment_count": _non_negative(raw.get("prior_payment_count")),
        "prior_chargeback_count": _non_negative(raw.get("prior_chargeback_count")),
        "avg_amount_minor": avg_amount,
        "amount_to_avg_ratio": amount_to_avg,
        "amount_over_historic_max": over_max,
        "payment_count_1h": count_1h,
        "payment_count_24h": count_24h,
        "amount_sum_24h_minor": _non_negative(raw.get("amount_sum_24h_minor")),
        "failed_payment_count_24h": _non_negative(raw.get("failed_payment_count_24h")),
        "seconds_since_last_payment": _non_negative(raw.get("seconds_since_last_payment")),
        "is_new_device": _flag(raw.get("is_new_device")),
        "distinct_devices_30d": _non_negative(raw.get("distinct_devices_30d")),
        "is_new_country": _flag(raw.get("is_new_country")),
        "distinct_countries_30d": _non_negative(raw.get("distinct_countries_30d")),
        "is_elevated_risk_country": 1.0 if country in ELEVATED_RISK_COUNTRIES else 0.0,
        "distance_from_last_km": _non_negative(raw.get("distance_from_last_km")),
        "has_geo": 1.0 if raw.get("has_geo") else 0.0,
        "is_new_payee": _flag(raw.get("is_new_payee")),
        "payee_is_high_risk_category": (
            1.0 if category in HIGH_RISK_MERCHANT_CATEGORIES else 0.0
        ),
    }


def vectorise(values: Mapping[str, float], names: Sequence[str] = FEATURE_NAMES) -> np.ndarray:
    """
    Lays named features out in the model's column order.

    ``names`` defaults to the current contract but is overridable so a loaded artifact can
    insist on the order it was *trained* with. That is the guard against a future edit to
    :data:`FEATURE_NAMES` silently reinterpreting an existing model.
    """
    missing = [name for name in names if name not in values]
    if missing:
        raise KeyError(
            f"feature vector is incomplete, missing {missing}. Serving a partial vector "
            f"would place zeros where the model expects real values."
        )
    row = np.empty((1, len(names)), dtype=np.float64)
    for index, name in enumerate(names):
        row[0, index] = values[name]
    return row


def snapshot(values: Mapping[str, float]) -> dict[str, float | None]:
    """
    The feature values in a form safe to persist as JSON.

    ``NaN`` is not valid JSON, so an absent value becomes ``null`` — which is what it
    means. Serialising it as ``0`` would make a dispute investigation read "no distance
    from last location" as "same location", inverting the signal.
    """
    result: dict[str, float | None] = {}
    for name in FEATURE_NAMES:
        value = values.get(name)
        if value is None or (isinstance(value, float) and np.isnan(value)):
            result[name] = None
        else:
            result[name] = round(float(value), 6)
    return result
