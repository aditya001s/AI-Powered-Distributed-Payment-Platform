"""
Tests for the synthetic generator.

The generator's job is not to be realistic in the abstract — it is to be *hard enough*. A
naive earlier version scored AUC 0.9999, which said nothing except that one column separated
the classes. Several of these tests exist specifically to stop that regressing, because the
symptom is a metric that looks better.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.dataset import ARCHETYPES, ARCHETYPE_WEIGHTS, FRIENDLY_FRAUD_SHARE, generate
from app.features import FEATURE_NAMES


class TestShape:
    def test_matches_the_feature_contract(self):
        data = generate(rows=500, fraud_rate=0.05, seed=1)

        assert data.features.shape == (500, len(FEATURE_NAMES))
        assert data.labels.shape == (500,)
        assert len(data.archetypes) == 500

    def test_archetype_weights_sum_to_one(self):
        assert sum(ARCHETYPE_WEIGHTS) == pytest.approx(1.0)
        assert len(ARCHETYPE_WEIGHTS) == len(ARCHETYPES)

    def test_observed_fraud_rate_is_near_the_requested_one(self):
        data = generate(rows=20_000, fraud_rate=0.02, seed=3)
        assert data.observed_fraud_rate == pytest.approx(0.02, abs=0.004)

    def test_both_classes_are_present_even_in_a_tiny_dataset(self):
        """
        Tests generate small datasets, and a single-class one would fail deep inside sklearn
        with an error that says nothing about the cause.
        """
        data = generate(rows=20, fraud_rate=0.01, seed=5)
        assert 0 < data.fraud_count < 20

    def test_rejects_a_dataset_that_is_half_fraud(self):
        """
        A balanced dataset would calibrate the model for a world that does not exist, and the
        probabilities would be wrong by two orders of magnitude in production.
        """
        with pytest.raises(ValueError, match="does not exist"):
            generate(rows=100, fraud_rate=0.6, seed=1)

    def test_rejects_a_non_positive_row_count(self):
        with pytest.raises(ValueError, match="positive"):
            generate(rows=0, fraud_rate=0.02, seed=1)


class TestDeterminism:
    def test_the_same_seed_reproduces_the_dataset_exactly(self):
        """
        This is what makes a disputed score reproducible. Without it, rebuilding the image
        would produce a different model and last quarter's decision could not be explained.
        """
        first = generate(rows=800, fraud_rate=0.05, seed=11)
        second = generate(rows=800, fraud_rate=0.05, seed=11)

        assert np.array_equal(first.features, second.features, equal_nan=True)
        assert np.array_equal(first.labels, second.labels)
        assert first.archetypes == second.archetypes

    def test_a_different_seed_produces_a_different_dataset(self):
        first = generate(rows=800, fraud_rate=0.05, seed=11)
        second = generate(rows=800, fraud_rate=0.05, seed=12)
        assert not np.array_equal(first.features, second.features, equal_nan=True)


class TestDifficulty:
    """
    The tests that stop the problem becoming trivially separable again.

    Each one asserts that a signal appears on *both* sides. If any of these starts failing,
    the metrics will improve and mean less.
    """

    @staticmethod
    @pytest.fixture(scope="class")
    def sample():
        return generate(rows=30_000, fraud_rate=0.05, seed=17)

    @staticmethod
    def _column(data, name: str) -> np.ndarray:
        return data.features[:, FEATURE_NAMES.index(name)]

    @pytest.mark.parametrize(
        "feature",
        [
            "is_new_device",
            "is_new_country",
            "is_elevated_risk_country",
            "payee_is_high_risk_category",
            "is_round_amount",
            "amount_over_historic_max",
            "is_new_payee",
        ],
    )
    def test_no_single_signal_is_exclusive_to_fraud(self, sample, feature):
        """
        A signal that only ever appears on fraud is a giveaway, not a feature.

        The model would key off it, the metrics would look excellent, and the first legitimate
        customer who tripped it would be declined on that alone.
        """
        column = self._column(sample, feature)
        legitimate_hits = int(np.nansum(column[sample.labels == 0] == 1.0))

        assert legitimate_hits > 0, (
            f"{feature} never fires on legitimate traffic, so it separates the classes by "
            f"itself. Add overlap in the generator rather than accepting the better metric."
        )

    def test_fraud_sometimes_uses_a_known_device_and_a_familiar_country(self, sample):
        """
        Session hijack rather than stolen credentials. This is what keeps recall short of 1.0
        and stops the model treating a known device as proof of innocence.
        """
        fraud = sample.labels == 1
        known_device = self._column(sample, "is_new_device")[fraud] == 0.0
        same_country = self._column(sample, "is_new_country")[fraud] == 0.0

        assert known_device.sum() > 0
        assert same_country.sum() > 0

    def test_friendly_fraud_is_labelled_fraud_and_looks_ordinary(self, sample):
        """
        The ceiling on recall. These are payments the real customer made and then charged
        back, so nothing distinguishes them — and a model that appeared to catch them would
        mean the generator had leaked a tell.
        """
        friendly = [
            index for index, archetype in enumerate(sample.archetypes)
            if archetype == "friendly_fraud"
        ]
        assert friendly, "friendly fraud must be present or recall is unrealistically easy"
        assert all(sample.labels[index] == 1 for index in friendly)

        share = len(friendly) / max(1, int(sample.labels.sum()))
        assert share == pytest.approx(FRIENDLY_FRAUD_SHARE, abs=0.05)

    def test_unreported_fraud_contaminates_the_clean_labels(self, sample):
        """
        The ceiling on precision: genuinely fraudulent payments that were never charged back
        and therefore carry a clean label.
        """
        unreported = [
            index for index, archetype in enumerate(sample.archetypes)
            if archetype and archetype.startswith("unreported_")
        ]
        assert unreported, "unreported fraud must be present or precision is unrealistic"
        assert all(sample.labels[index] == 0 for index in unreported)

    def test_missing_values_are_present_in_training(self, sample):
        """
        The NaN split directions have to be trained.

        If every field were always present, the first real request with a missing device would
        take an arbitrary path through the trees.
        """
        nan_fraction = np.isnan(sample.features).mean(axis=0)
        assert (nan_fraction > 0).sum() >= 5, (
            "too few features are ever absent; serving will hit untrained NaN paths"
        )
        # ...but not so much that a column is mostly empty and carries nothing.
        assert nan_fraction.max() < 0.5


class TestConsistency:
    """
    Invariants the generated data must satisfy, because serving repairs the same ones.

    A generator that produced rows serving could never produce would train the model on a
    distribution it never sees — the silent half of train/serve skew.
    """

    @staticmethod
    @pytest.fixture(scope="class")
    def sample():
        return generate(rows=8_000, fraud_rate=0.05, seed=23)

    @staticmethod
    def _column(data, name: str) -> np.ndarray:
        return data.features[:, FEATURE_NAMES.index(name)]

    def test_the_24h_window_contains_the_1h_window(self, sample):
        one_hour = self._column(sample, "payment_count_1h")
        one_day = self._column(sample, "payment_count_24h")

        assert np.all(one_day >= one_hour), (
            "a 24-hour window cannot hold fewer payments than the hour inside it"
        )

    def test_a_zero_prior_count_means_a_zero_prior_total(self, sample):
        """
        The window excludes the payment being scored, so no prior payments means no prior
        total. Deriving the total from the amount instead — as an earlier version did — made
        every row imply activity that had not happened.
        """
        count = self._column(sample, "payment_count_24h")
        total = self._column(sample, "amount_sum_24h_minor")

        assert np.all(total[count == 0.0] == 0.0)

    def test_the_prior_total_may_be_smaller_than_the_current_amount(self, sample):
        """
        Because the window excludes this payment. An earlier version could not produce this
        row at all, so a real request where it held scored from an untrained region.
        """
        amount = self._column(sample, "amount_minor")
        total = self._column(sample, "amount_sum_24h_minor")

        assert np.any(total < amount)

    def test_no_feature_is_infinite(self, sample):
        """
        Infinity would poison a histogram bin edge and distort every split against that
        column. Absence is expressed as NaN; nothing is ever infinite.
        """
        assert not np.isinf(sample.features).any()

    def test_amounts_are_never_negative(self, sample):
        assert np.all(np.nan_to_num(self._column(sample, "amount_minor"), nan=0.0) >= 0.0)
