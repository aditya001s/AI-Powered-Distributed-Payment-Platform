"""
The training dataset.

**This data is synthetic, and the README says so where anyone will see it.** There is no
real labelled fraud data in this project, and there is no honest way to pretend otherwise.
What this generator does is impose a *documented causal structure* — four fraud archetypes,
each expressing itself through a different subset of the features — and then draw the
observable signals from overlapping distributions.

The consequence has to be stated plainly, because it is easy to misread the metrics the
training run prints: **the reported AUC measures how well the model recovers a structure we
ourselves imposed.** It is a check that the pipeline learns, that calibration works, and
that the feature contract carries signal. It is not a claim about real-world fraud
performance, and no number produced here should ever be quoted as one.

Three properties are deliberate, and the first two exist because the naive version of this
generator scored AUC 0.9999 — a number that says nothing except that the archetypes had been
made separable by a single column.

*Overlap.* Legitimate customers also travel, also buy new phones, also occasionally make the
largest payment of their lives, and a few of them live in the countries on the elevated-risk
list. Roughly one legitimate payment in eight is given some of the fraud archetypes' signals,
and no single signal is exclusive to fraud.

*Label noise, in both directions.* Real fraud labels are neither complete nor correct, and a
generator that pretends otherwise produces a model that cannot exist:

* **Unreported fraud** — a small share of genuinely fraudulent payments are never charged back
  and therefore carry a clean label. They contaminate the negative class, which is what caps
  achievable precision.
* **Friendly fraud** — a share of chargebacks are raised by the real customer on a payment they
  actually made. They look entirely ordinary and are labelled fraudulent, which is what caps
  achievable recall.

Together these put a ceiling on the metrics that no model can beat, which is exactly the
situation a real fraud team is in.

*One code path.* Records are generated as raw request payloads and pushed through
:func:`app.features.derive`, the same function serving uses.

Vectorising the feature derivation for speed would create a second implementation, and the
two would drift — which is the classic train/serve skew bug, and it fails silently. Training
takes a few seconds longer instead.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import numpy as np

from .features import FEATURE_NAMES, derive

#: Fraud archetypes and their share of the fraud population. Each leans on a different
#: part of the feature space, so a model that learns only one of them scores visibly worse
#: than one that learns all four.
ARCHETYPES: tuple[str, ...] = (
    "account_takeover",
    "card_testing",
    "bust_out",
    "mule_cashout",
)
ARCHETYPE_WEIGHTS: tuple[float, ...] = (0.40, 0.25, 0.20, 0.15)

#: Share of legitimate payments handed some of the fraud archetypes' signals. Customers
#: travel, replace phones, and occasionally make an unusually large payment.
LEGITIMATE_CONFUSION_RATE: float = 0.13

#: Share of *clean-labelled* rows that are actually fraud nobody reported. Contaminates the
#: negative class and caps achievable precision. See the module docstring.
UNREPORTED_FRAUD_SHARE: float = 0.0035

#: Share of *fraud-labelled* rows that are ordinary payments the real customer charged back.
#: Indistinguishable from legitimate traffic, and caps achievable recall.
FRIENDLY_FRAUD_SHARE: float = 0.12

#: Countries legitimate traffic comes from. The elevated-risk codes appear with small but
#: non-zero probability, because customers do live and travel there — and a model trained on
#: data where they never do would learn that geography alone is proof.
LEGITIMATE_COUNTRIES: tuple[str, ...] = ("IN", "IN", "IN", "IN", "AE", "SG", "GB", "US", "XX", "A1")
LEGITIMATE_COUNTRY_WEIGHTS: tuple[float, ...] = (
    0.62, 0.10, 0.06, 0.04, 0.05, 0.04, 0.03, 0.023, 0.005, 0.002,
)


@dataclass(frozen=True)
class Dataset:
    """A generated dataset, already in the model's column order."""

    features: np.ndarray
    labels: np.ndarray
    archetypes: list[str | None]

    def __post_init__(self) -> None:
        if self.features.shape[0] != self.labels.shape[0]:
            raise ValueError("feature and label counts disagree")
        if self.features.shape[1] != len(FEATURE_NAMES):
            raise ValueError(
                f"expected {len(FEATURE_NAMES)} columns, got {self.features.shape[1]}"
            )

    @property
    def fraud_count(self) -> int:
        return int(self.labels.sum())

    @property
    def observed_fraud_rate(self) -> float:
        return float(self.labels.mean())


def _legitimate_record(rng: np.random.Generator) -> dict[str, Any]:
    """
    An ordinary payment from an established customer.

    Amounts are log-normal around the account's own mean, which is what makes
    ``amount_to_avg_ratio`` a useful feature rather than a proxy for wealth.
    """
    account_age = float(rng.integers(30, 2_200))
    prior_payments = float(rng.integers(5, 900))

    # The account's own norm, spread across two orders of magnitude between customers.
    avg_amount = float(np.exp(rng.normal(np.log(150_000.0), 0.9)))
    amount = float(avg_amount * np.exp(rng.normal(0.0, 0.55)))
    max_amount = float(max(amount, avg_amount * np.exp(abs(rng.normal(0.9, 0.5)))))

    payment_type = str(rng.choice(
        ["P2P_TRANSFER", "MERCHANT_PAYMENT", "WALLET_WITHDRAWAL"], p=[0.55, 0.40, 0.05],
    ))
    if payment_type == "WALLET_WITHDRAWAL":
        # People withdraw round sums. Modelling that matters: without it, roundness would
        # appear almost only on mule cash-outs, the model would treat it as near-proof of
        # fraud, and a customer taking out an even 5,000 would be flagged on that alone.
        amount = float(max(1, round(amount / 100_000.0)) * 100_000)

    hour = int(rng.choice(
        np.arange(24),
        # Daytime-weighted: real retail traffic is not uniform across the clock, and a
        # model trained on uniform hours would treat 03:00 as unremarkable.
        p=_normalised([
            0.4, 0.3, 0.2, 0.2, 0.3, 0.6, 1.2, 2.2, 3.4, 4.2, 4.8, 5.2,
            5.4, 5.2, 4.9, 4.7, 4.6, 4.8, 5.1, 4.9, 4.2, 3.1, 1.9, 1.0,
        ]),
    ))

    return {
        "amount_minor": round(amount),
        "avg_amount_minor": round(avg_amount),
        "max_amount_minor": round(max_amount),
        "hour_of_day": hour,
        "day_of_week": int(rng.integers(1, 8)),
        "payment_type": payment_type,
        "account_age_days": account_age,
        "prior_payment_count": prior_payments,
        "prior_chargeback_count": float(rng.binomial(1, 0.006)),
        "payment_count_1h": float(rng.choice([0, 1, 2], p=[0.82, 0.15, 0.03])),
        "payment_count_24h": float(rng.choice([0, 1, 2, 3, 4], p=[0.42, 0.30, 0.16, 0.08, 0.04])),
        # Filled in by _finalise once the final count and amount are known, because several
        # archetypes rewrite both and a prior total has to stay consistent with them.
        "amount_sum_24h_minor": None,
        # A customer whose card is expiring retries, and a bank declines for reasons that
        # have nothing to do with fraud. A clean history here would make any failure decisive.
        "failed_payment_count_24h": float(rng.choice([0, 1, 2, 3, 5], p=[0.88, 0.06, 0.03, 0.02, 0.01])),
        "seconds_since_last_payment": float(rng.integers(1_800, 1_900_000)),
        "is_new_device": bool(rng.binomial(1, 0.05)),
        "distinct_devices_30d": float(rng.choice([1, 2, 3, 4], p=[0.66, 0.24, 0.08, 0.02])),
        "is_new_country": bool(rng.binomial(1, 0.04)),
        "distinct_countries_30d": float(rng.choice([1, 2, 3], p=[0.88, 0.10, 0.02])),
        "country_code": str(rng.choice(LEGITIMATE_COUNTRIES, p=_normalised(
            list(LEGITIMATE_COUNTRY_WEIGHTS)))),
        "distance_from_last_km": float(abs(rng.normal(9.0, 25.0))),
        "has_geo": bool(rng.binomial(1, 0.80)),
        "is_new_payee": bool(rng.binomial(1, 0.22)),
        # Legitimate customers do withdraw cash, gamble and buy crypto. A category list that
        # only ever appeared on fraud would let one column decide every payment.
        "payee_merchant_category": str(rng.choice(
            ["5411", "5812", "4111", "5732", "6011", "7995", "6051"],
            p=[0.30, 0.25, 0.16, 0.16, 0.07, 0.04, 0.02],
        )),
    }


def _confuse(record: dict[str, Any], rng: np.random.Generator) -> None:
    """
    Gives a legitimate payment one or two fraud-shaped signals, in place.

    This is what stops the problem being trivially separable. A customer on holiday really
    does transact from a new country on a new device, and a model that treats that
    combination as sufficient evidence would decline a large share of genuine travel.
    """
    for signal in rng.choice(
        ["travel", "new_device", "large_purchase", "late_night", "burst", "new_payee_cashout"],
        # Up to three at once: a customer landing abroad with a new phone at 02:00 is a
        # perfectly ordinary Tuesday, and the model has to survive it.
        size=int(rng.integers(1, 4)),
        replace=False,
    ):
        if signal == "travel":
            record["is_new_country"] = True
            record["distinct_countries_30d"] = float(rng.integers(2, 5))
            record["distance_from_last_km"] = float(rng.uniform(400.0, 9_000.0))
            record["has_geo"] = True
        elif signal == "new_device":
            record["is_new_device"] = True
            record["distinct_devices_30d"] = float(rng.integers(2, 5))
        elif signal == "large_purchase":
            base = record.get("max_amount_minor") or record["amount_minor"]
            record["amount_minor"] = round(float(base) * float(rng.uniform(1.05, 6.0)))
        elif signal == "late_night":
            record["hour_of_day"] = int(rng.integers(0, 5))
        elif signal == "burst":
            record["payment_count_1h"] = float(rng.integers(3, 12))
            record["payment_count_24h"] = float(rng.integers(6, 30))
            record["seconds_since_last_payment"] = float(rng.integers(20, 900))
        elif signal == "new_payee_cashout":
            record["is_new_payee"] = True
            record["payment_type"] = "WALLET_WITHDRAWAL"
            record["payee_merchant_category"] = "6011"
            record["amount_minor"] = float(int(rng.integers(2, 30)) * 100_000)


def _fraud_record(archetype: str, rng: np.random.Generator) -> dict[str, Any]:
    """
    A fraudulent payment of one archetype.

    Each archetype is a plausible attack with a distinct fingerprint, so the feature set
    has to do real work rather than keying off one column.
    """
    record = _legitimate_record(rng)

    if archetype == "account_takeover":
        # Someone else is on the account: unfamiliar device and place, and they are in a
        # hurry to move more than the customer ever would.
        record["is_new_device"] = True
        record["distinct_devices_30d"] = float(rng.integers(2, 6))
        record["is_new_country"] = True
        record["distinct_countries_30d"] = float(rng.integers(2, 5))
        record["distance_from_last_km"] = float(rng.uniform(600.0, 14_000.0))
        base = record.get("avg_amount_minor") or record["amount_minor"]
        record["amount_minor"] = round(float(base) * float(rng.uniform(2.2, 11.0)))
        record["hour_of_day"] = int(rng.choice(np.arange(24), p=_normalised(
            [3.2, 3.4, 3.0, 2.6, 2.2, 1.4, 0.9, 0.7, 0.6, 0.6, 0.7, 0.7,
             0.8, 0.8, 0.8, 0.9, 1.0, 1.1, 1.4, 1.8, 2.2, 2.6, 3.0, 3.2],
        )))
        record["is_new_payee"] = True
        record["seconds_since_last_payment"] = float(rng.integers(60, 40_000))
        # Someone with stolen access is usually in a hurry, but not always: patient
        # attackers make one payment and leave. Spanning both keeps the velocity features
        # from being read as evidence *against* a takeover, which is what happened when this
        # archetype inherited an ordinary customer's near-zero counts.
        record["payment_count_1h"] = float(rng.integers(0, 5))
        record["payment_count_24h"] = float(rng.integers(1, 9))

    elif archetype == "card_testing":
        # Probing which credentials still work: many small attempts, several refused. The
        # ranges start low and overlap a legitimate shopping burst, so velocity alone is
        # suggestive rather than conclusive.
        record["amount_minor"] = round(float(rng.uniform(100.0, 12_000.0)))
        record["payment_count_1h"] = float(rng.integers(3, 25))
        record["payment_count_24h"] = float(rng.integers(5, 60))
        record["failed_payment_count_24h"] = float(rng.integers(1, 22))
        record["seconds_since_last_payment"] = float(rng.integers(3, 600))
        record["is_new_payee"] = True
        record["account_age_days"] = float(rng.integers(0, 400))
        record["prior_payment_count"] = float(rng.integers(0, 60))

    elif archetype == "bust_out":
        # A patient account that behaved normally and then emptied itself in one move.
        record["account_age_days"] = float(rng.integers(120, 900))
        record["prior_payment_count"] = float(rng.integers(30, 400))
        base = record.get("max_amount_minor") or record["amount_minor"]
        record["amount_minor"] = round(float(base) * float(rng.uniform(1.6, 5.0)))
        record["is_new_payee"] = True
        record["payee_merchant_category"] = str(rng.choice(["6051", "7995", "6011"]))
        record["is_new_device"] = bool(rng.binomial(1, 0.45))

    elif archetype == "mule_cashout":
        # Moving someone else's money out: round sums, unusual destination, cash-like. Only
        # two thirds route through an elevated-risk country, so that column stays a signal
        # rather than a giveaway.
        record["payment_type"] = "WALLET_WITHDRAWAL"
        record["amount_minor"] = float(int(rng.integers(2, 40)) * 100_000)
        if rng.binomial(1, 0.65):
            record["country_code"] = str(rng.choice(["XX", "ZZ", "A1", "A2"]))
        record["is_new_payee"] = True
        record["is_new_country"] = True
        record["distinct_countries_30d"] = float(rng.integers(2, 6))
        record["payment_count_24h"] = float(rng.integers(3, 14))
        record["payee_merchant_category"] = str(rng.choice(["6011", "6051"], p=[0.7, 0.3]))

    else:  # pragma: no cover - guarded by the caller's archetype list
        raise ValueError(f"unknown archetype {archetype!r}")

    # Fraud frequently does not look like fraud. A quarter of the time the attacker is on a
    # device the account already trusts — a hijacked session rather than a stolen credential —
    # and a quarter of the time they are in the customer's own country. This is what keeps
    # recall short of 1.0 and stops any single signal being decisive.
    if rng.binomial(1, 0.25):
        record["is_new_device"] = False
        record["distinct_devices_30d"] = 1.0
    if rng.binomial(1, 0.25):
        record["is_new_country"] = False
        record["distance_from_last_km"] = float(abs(rng.normal(12.0, 30.0)))
    if rng.binomial(1, 0.20):
        # Patient attackers wait for business hours precisely because 03:00 is watched.
        record["hour_of_day"] = int(rng.integers(9, 19))

    return record


def _finalise(record: dict[str, Any], rng: np.random.Generator) -> None:
    """
    Derives the prior 24-hour total from the final count and amount, in place.

    Called last because the archetypes and :func:`_confuse` both rewrite the amount and the
    counts, and a prior total computed before them would contradict them. The window
    excludes the payment being scored, so a first-attempt-of-the-day record correctly gets a
    prior total of zero — which is a real and common state, not a missing value.
    """
    count_24h = float(record.get("payment_count_24h") or 0.0)
    if count_24h <= 0.0:
        record["amount_sum_24h_minor"] = 0.0
        return

    # Earlier payments that day are drawn around this account's own norm rather than around
    # the current amount, so a single large payment does not imply a large prior total.
    typical = float(record.get("avg_amount_minor") or record["amount_minor"])
    record["amount_sum_24h_minor"] = round(
        count_24h * typical * float(rng.uniform(0.5, 1.6))
    )


def _drop_optional_signals(record: dict[str, Any], rng: np.random.Generator) -> None:
    """
    Removes signals that are genuinely unavailable for some payments, in place.

    Serving will see ``None`` for these — an API-channel payment has no device, and a
    customer who refused location permission has no coordinates. Training on data where
    every field is always present would leave the ``NaN`` split directions untrained, so
    the first real request with a missing field would take an arbitrary path.
    """
    if not record.get("has_geo"):
        record["distance_from_last_km"] = None
    if rng.binomial(1, 0.06):
        record["is_new_device"] = None
    if rng.binomial(1, 0.04):
        record["hour_of_day"] = None
        record["day_of_week"] = None
    if rng.binomial(1, 0.05):
        # A brand-new account has no history to average.
        record["avg_amount_minor"] = None
        record["max_amount_minor"] = None
        record["seconds_since_last_payment"] = None


def _normalised(weights: list[float]) -> np.ndarray:
    array = np.asarray(weights, dtype=np.float64)
    return array / array.sum()


def generate(rows: int, fraud_rate: float, seed: int) -> Dataset:
    """
    Generates a dataset.

    :param rows: total records
    :param fraud_rate: share labelled fraudulent, matching the platform's assumed base rate
    :param seed: fixed so a rebuild reproduces the model exactly
    """
    if rows <= 0:
        raise ValueError("rows must be positive")
    if not 0.0 < fraud_rate < 0.5:
        raise ValueError(
            "fraud_rate must be a small positive share; a dataset that is half fraud "
            "would train a model calibrated for a world that does not exist"
        )

    rng = np.random.default_rng(seed)

    labels = rng.binomial(1, fraud_rate, size=rows).astype(np.int8)
    # Guarantee both classes are present even for a tiny dataset, which tests use.
    if labels.sum() == 0:
        labels[0] = 1
    if labels.sum() == rows:
        labels[0] = 0

    chosen_archetypes = rng.choice(ARCHETYPES, size=rows, p=_normalised(list(ARCHETYPE_WEIGHTS)))
    confuse_draw = rng.binomial(1, LEGITIMATE_CONFUSION_RATE, size=rows)
    friendly_draw = rng.binomial(1, FRIENDLY_FRAUD_SHARE, size=rows)
    unreported_draw = rng.binomial(1, UNREPORTED_FRAUD_SHARE, size=rows)

    matrix = np.empty((rows, len(FEATURE_NAMES)), dtype=np.float64)
    archetypes: list[str | None] = []

    for index in range(rows):
        if labels[index] == 1 and not friendly_draw[index]:
            # Genuine fraud, reported.
            archetype = str(chosen_archetypes[index])
            record = _fraud_record(archetype, rng)
            archetypes.append(archetype)

        elif labels[index] == 1:
            # Friendly fraud: the real customer made this payment and then charged it back.
            # Labelled fraudulent and shaped exactly like legitimate traffic, so no model can
            # separate it. This is the ceiling on recall.
            record = _legitimate_record(rng)
            archetypes.append("friendly_fraud")

        elif unreported_draw[index]:
            # Fraud that was never charged back, so it carries a clean label. Contaminates
            # the negative class, which is the ceiling on precision.
            archetype = str(chosen_archetypes[index])
            record = _fraud_record(archetype, rng)
            archetypes.append(f"unreported_{archetype}")

        else:
            record = _legitimate_record(rng)
            if confuse_draw[index]:
                _confuse(record, rng)
            archetypes.append(None)

        _finalise(record, rng)
        _drop_optional_signals(record, rng)

        # The same derivation serving uses. See the module docstring.
        values = derive(record)
        for column, name in enumerate(FEATURE_NAMES):
            matrix[index, column] = values[name]

    return Dataset(features=matrix, labels=labels.astype(np.int8), archetypes=archetypes)
