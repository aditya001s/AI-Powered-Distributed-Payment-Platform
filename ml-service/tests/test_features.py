"""
Tests for the feature contract.

The contract is where train/serve skew hides. Every bug this file guards against is silent:
the model still returns a number, and the number is wrong.
"""

from __future__ import annotations

import math

import numpy as np
import pytest

from app.features import (
    FEATURE_COUNT,
    FEATURE_DESCRIPTIONS,
    FEATURE_NAMES,
    derive,
    snapshot,
    vectorise,
)


class TestContract:
    def test_every_feature_is_described(self):
        """An undescribed feature reaches an analyst as a bare identifier in an explanation."""
        assert set(FEATURE_NAMES) == set(FEATURE_DESCRIPTIONS)

    def test_names_are_unique(self):
        """A duplicate name would silently shadow a column in the vector."""
        assert len(FEATURE_NAMES) == len(set(FEATURE_NAMES)) == FEATURE_COUNT

    def test_derive_produces_exactly_the_contract(self, ordinary_payment):
        assert set(derive(ordinary_payment)) == set(FEATURE_NAMES)

    def test_vectorise_refuses_an_incomplete_vector(self):
        """
        A partial vector must fail loudly.

        Filling the gap with zeros would place "no prior chargebacks" where the truth is
        "unknown", and nothing downstream could detect it.
        """
        with pytest.raises(KeyError, match="incomplete"):
            vectorise({"amount_minor": 1.0})

    def test_vectorise_honours_a_stored_column_order(self, ordinary_payment):
        """
        A loaded model insists on the order it was trained with.

        This is the guard against a future edit to FEATURE_NAMES reinterpreting an existing
        artifact: a boosted tree indexes columns positionally, so a reordered contract does
        not fail, it applies every split to the wrong feature.
        """
        values = derive(ordinary_payment)
        reversed_order = tuple(reversed(FEATURE_NAMES))

        forward = vectorise(values, FEATURE_NAMES)
        backward = vectorise(values, reversed_order)

        assert np.allclose(forward[0], backward[0][::-1], equal_nan=True)


class TestAbsence:
    """Absence is a signal. These tests exist because imputing would destroy it."""

    def test_missing_history_stays_nan_rather_than_zero(self):
        values = derive({"amount_minor": 100_000})

        assert math.isnan(values["avg_amount_minor"])
        assert math.isnan(values["amount_to_avg_ratio"])
        assert math.isnan(values["seconds_since_last_payment"])

    def test_absent_hour_leaves_the_night_flag_unknown(self):
        """
        Not-known must not become not-night.

        Defaulting it to 0 would tell the model "daytime" for every payment whose timestamp
        was unavailable, which is a lie in exactly the population most likely to be automated.
        """
        values = derive({"amount_minor": 100_000, "hour_of_day": None})
        assert math.isnan(values["is_night"])

    def test_tri_state_boolean_distinguishes_false_from_unknown(self):
        assert derive({"amount_minor": 1, "is_new_device": False})["is_new_device"] == 0.0
        assert derive({"amount_minor": 1, "is_new_device": True})["is_new_device"] == 1.0
        assert math.isnan(derive({"amount_minor": 1})["is_new_device"])

    def test_snapshot_serialises_absence_as_null(self):
        """
        NaN is not valid JSON, and 0 would invert the meaning.

        A dispute investigation reading "distance_from_last_km: 0" would conclude the payment
        came from the customer's usual location, when in fact no location was available.
        """
        stored = snapshot(derive({"amount_minor": 100_000}))

        assert stored["avg_amount_minor"] is None
        assert stored["amount_minor"] == 100_000.0
        assert all(value is None or isinstance(value, float) for value in stored.values())


class TestDerivations:
    def test_ratio_to_the_account_norm(self, ordinary_payment):
        """
        The most useful transformation in the set: 'big' becomes 'big for this customer'.

        Without it the model would learn that wealthy customers are fraudulent.
        """
        values = derive({**ordinary_payment, "amount_minor": 700_000, "avg_amount_minor": 140_000})
        assert values["amount_to_avg_ratio"] == pytest.approx(5.0)

    def test_ratio_is_unknown_rather_than_infinite_when_the_norm_is_zero(self, ordinary_payment):
        """Infinity would poison a histogram bin edge and distort every split against it."""
        values = derive({**ordinary_payment, "avg_amount_minor": 0})
        assert math.isnan(values["amount_to_avg_ratio"])

    def test_exceeding_the_historic_maximum_is_flagged(self, ordinary_payment):
        assert derive({**ordinary_payment, "amount_minor": 500_000})["amount_over_historic_max"] == 1.0
        assert derive({**ordinary_payment, "amount_minor": 300_000})["amount_over_historic_max"] == 0.0

    def test_log_amount_is_finite_at_zero(self):
        """log1p rather than log, so a zero amount stays a number."""
        assert derive({"amount_minor": 0})["amount_log"] == 0.0

    def test_night_window_is_midnight_to_five(self, ordinary_payment):
        for hour in (0, 3, 4):
            assert derive({**ordinary_payment, "hour_of_day": hour})["is_night"] == 1.0
        for hour in (5, 12, 23):
            assert derive({**ordinary_payment, "hour_of_day": hour})["is_night"] == 0.0

    def test_weekend_uses_iso_day_numbering(self, ordinary_payment):
        """Monday is 1, so 6 and 7 are the weekend. Off-by-one here shifts every day."""
        assert derive({**ordinary_payment, "day_of_week": 6})["is_weekend"] == 1.0
        assert derive({**ordinary_payment, "day_of_week": 7})["is_weekend"] == 1.0
        assert derive({**ordinary_payment, "day_of_week": 1})["is_weekend"] == 0.0
        assert derive({**ordinary_payment, "day_of_week": 5})["is_weekend"] == 0.0

    def test_elevated_risk_country_is_case_insensitive(self, ordinary_payment):
        assert derive({**ordinary_payment, "country_code": "xx"})["is_elevated_risk_country"] == 1.0
        assert derive({**ordinary_payment, "country_code": "IN"})["is_elevated_risk_country"] == 0.0

    def test_high_risk_merchant_categories_are_recognised(self, ordinary_payment):
        for category in ("6011", "7995", "6051", "5967"):
            values = derive({**ordinary_payment, "payee_merchant_category": category})
            assert values["payee_is_high_risk_category"] == 1.0
        values = derive({**ordinary_payment, "payee_merchant_category": "5411"})
        assert values["payee_is_high_risk_category"] == 0.0

    def test_round_amount_detection(self, ordinary_payment):
        assert derive({**ordinary_payment, "amount_minor": 500_000})["is_round_amount"] == 1.0
        assert derive({**ordinary_payment, "amount_minor": 512_345})["is_round_amount"] == 0.0
        # Zero is not "round", it is nothing.
        assert derive({**ordinary_payment, "amount_minor": 0})["is_round_amount"] == 0.0


class TestRobustness:
    def test_the_24h_window_contains_the_1h_window(self, ordinary_payment):
        """
        A miscomputed aggregate is repaired rather than passed through.

        The pair (1h=17, 24h=2) describes a world that never appeared in training, and the
        model would extrapolate from it silently. Repairing beats rejecting: a caller's
        arithmetic bug should not decline a customer's payment.
        """
        values = derive({**ordinary_payment, "payment_count_1h": 17, "payment_count_24h": 2})
        assert values["payment_count_24h"] == 17.0

    def test_negative_counts_are_clamped(self, ordinary_payment):
        """
        Outside the trained range a value lands in the outermost histogram bin and drags the
        prediction with it, so a negative count is clamped rather than trusted.
        """
        values = derive({**ordinary_payment, "prior_payment_count": -5})
        assert values["prior_payment_count"] == 0.0

    def test_infinity_becomes_unknown(self, ordinary_payment):
        values = derive({**ordinary_payment, "distance_from_last_km": float("inf")})
        assert math.isnan(values["distance_from_last_km"])

    def test_unparseable_values_become_unknown_rather_than_raising(self, ordinary_payment):
        """
        A malformed field must not fail the payment.

        Scoring on a partial vector is a worse outcome than a slightly less informed score,
        but crashing is worse than both: the saga would treat it as a fault and compensate a
        payment that had nothing wrong with it.
        """
        values = derive({**ordinary_payment, "account_age_days": "not-a-number"})
        assert math.isnan(values["account_age_days"])

    def test_hour_wraps_rather_than_escaping_the_range(self, ordinary_payment):
        assert derive({**ordinary_payment, "hour_of_day": 25})["hour_of_day"] == 1.0

    def test_derivation_is_pure(self, ordinary_payment):
        """The caller's dict must come back untouched; the generator relies on it."""
        before = dict(ordinary_payment)
        derive(ordinary_payment)
        assert ordinary_payment == before
