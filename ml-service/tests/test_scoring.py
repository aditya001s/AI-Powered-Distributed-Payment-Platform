"""
Tests for the scoring engine.

Two kinds of assertion here, and they are deliberately different in strength:

*Contract* assertions are exact — the score is the probability in percentage points, the
snapshot round-trips, the explanation is ranked and bounded, scoring is deterministic.

*Behavioural* assertions are directional and loose: a takeover-shaped payment must score
above an ordinary one, not above some specific number. Pinning a model's output to a constant
produces a test that fails on every retrain for no reason and teaches everyone to update the
number without reading it. Where an absolute floor is asserted at all, it is the one the
platform's own thresholds depend on.
"""

from __future__ import annotations

import pytest

from app.features import FEATURE_NAMES
from app.scoring import (
    DEFAULT_TOP_CONTRIBUTIONS,
    MIN_CONTRIBUTION_POINTS,
    feature_contract,
    to_risk_score,
)


class TestRiskScore:
    def test_the_score_is_the_probability_in_percentage_points(self):
        """
        Not a rescaled severity. The platform's thresholds are probabilities, so a score of 80
        has to be exactly the 0.80 boundary or FRAUD_HIGH_RISK_THRESHOLD means nothing.
        """
        assert to_risk_score(0.0) == 0
        assert to_risk_score(0.45) == 45
        assert to_risk_score(0.80) == 80
        assert to_risk_score(1.0) == 100

    def test_rounds_rather_than_truncates(self):
        """Truncating would read 0.799 as 79 and 0.800 as 80, a whole point apart."""
        assert to_risk_score(0.795) == 80
        assert to_risk_score(0.794) == 79

    def test_clamps_a_calibrator_overshoot(self):
        """Isotonic regression can emit a hair outside [0,1] on the boundary bins."""
        assert to_risk_score(-0.0001) == 0
        assert to_risk_score(1.0001) == 100

    def test_rejects_a_non_finite_probability(self):
        with pytest.raises(ValueError, match="finite"):
            to_risk_score(float("nan"))


class TestScoringContract:
    def test_the_score_matches_the_probability(self, engine, ordinary_payment):
        result = engine.score(ordinary_payment)
        assert result.risk_score == to_risk_score(result.fraud_probability)

    def test_reports_the_model_version(self, engine, ordinary_payment):
        """
        Recorded on every assessment so a disputed score can be tied to a specific build.
        """
        result = engine.score(ordinary_payment)
        assert result.model_version == engine.model_version
        assert result.model_version.startswith("fraud_hgb-")

    def test_the_snapshot_covers_the_whole_contract(self, engine, ordinary_payment):
        """
        The snapshot is what makes a score reproducible months later, so a partial one would
        make a dispute unanswerable.
        """
        result = engine.score(ordinary_payment)
        assert set(result.features) == set(FEATURE_NAMES)

    def test_the_snapshot_is_json_safe(self, engine):
        """NaN is not valid JSON. Absence must serialise as null, never as zero."""
        import json

        result = engine.score({"amount_minor": 250_000})
        # Round-trips without a NaN escaping, and absence survives as null.
        assert json.loads(json.dumps(result.features))["avg_amount_minor"] is None

    def test_scoring_is_deterministic(self, engine, ordinary_payment):
        first = engine.score(ordinary_payment)
        second = engine.score(ordinary_payment)

        assert first.fraud_probability == second.fraud_probability
        assert first.features == second.features

    def test_scores_a_request_with_almost_nothing_known(self, engine):
        """
        A payment from a brand-new account through a channel with no device context is a real
        request, not an error. It must produce a score rather than an exception — the saga
        would treat a fault as a technical failure and compensate a payment that was fine.
        """
        result = engine.score({"amount_minor": 500_000, "payment_type": "P2P_TRANSFER"})

        assert 0 <= result.risk_score <= 100
        assert sum(1 for value in result.features.values() if value is None) > 5

    def test_reports_its_own_latency(self, engine, ordinary_payment):
        result = engine.score(ordinary_payment)
        assert result.scoring_latency_ms >= 0


class TestExplanation:
    def test_contributions_are_ranked_by_absolute_effect(self, engine, card_testing_payment):
        result = engine.score(card_testing_payment)

        impacts = [abs(item.impact_points) for item in result.contributions]
        assert impacts == sorted(impacts, reverse=True)

    def test_respects_the_requested_limit(self, engine, card_testing_payment):
        result = engine.score(card_testing_payment, top_contributions=3)
        assert len(result.contributions) <= 3

    def test_defaults_to_a_readable_number(self, engine, card_testing_payment):
        result = engine.score(card_testing_payment)
        assert len(result.contributions) <= DEFAULT_TOP_CONTRIBUTIONS

    def test_drops_numerical_noise(self, engine, ordinary_payment):
        """
        Below a tenth of a point the number is calibrator noise, and presenting it as a reason
        a payment was held would be misleading.
        """
        result = engine.score(ordinary_payment)
        for item in result.contributions:
            assert abs(item.impact_points) >= MIN_CONTRIBUTION_POINTS

    def test_every_contribution_names_a_real_feature_and_describes_it(self, engine,
                                                                     card_testing_payment):
        """
        An analyst reading a held payment must not be shown a bare identifier.
        """
        result = engine.score(card_testing_payment)
        for item in result.contributions:
            assert item.feature in FEATURE_NAMES
            assert item.description and item.description != item.feature

    def test_direction_matches_the_sign(self, engine, card_testing_payment):
        result = engine.score(card_testing_payment)
        for item in result.contributions:
            expected = "increases" if item.impact_points > 0 else "decreases"
            assert item.direction == expected

    def test_a_high_scoring_payment_has_something_to_explain(self, engine, card_testing_payment):
        """
        A blocked payment with no explanation is unusable: nobody can act on it, and the
        customer cannot be told anything.
        """
        result = engine.score(card_testing_payment)

        assert result.risk_score > 45
        assert result.contributions, "a high score must come with reasons"
        assert any(item.impact_points > 0 for item in result.contributions)

    def test_the_explanation_reports_the_value_that_was_scored(self, engine,
                                                              card_testing_payment):
        """
        Ties the reason to the evidence: "failed attempts: 12" rather than "failed attempts
        mattered".
        """
        result = engine.score(card_testing_payment)
        snapshot = result.features

        for item in result.contributions:
            assert item.value == snapshot[item.feature]


class TestBehaviour:
    """
    Directional checks against the shapes the generator models as attacks.

    Loose on purpose. These assert that the model orders risk sensibly, not that it produces
    particular numbers — a test pinned to a constant would fail on every retrain and train
    everyone to update it without reading it.
    """

    def test_an_ordinary_payment_scores_low(self, engine, ordinary_payment):
        """
        The base rate is under 2%, so a well-calibrated model must put an unremarkable payment
        from an established customer far below the review threshold. If this rises, the alert
        rate rises with it and the fraud queue becomes unstaffable.
        """
        assert engine.score(ordinary_payment).risk_score < 20

    def test_card_testing_scores_above_the_review_threshold(self, engine, card_testing_payment):
        assert engine.score(card_testing_payment).risk_score >= 45

    def test_a_mule_cash_out_scores_above_the_review_threshold(self, engine, mule_payment):
        assert engine.score(mule_payment).risk_score >= 45

    def test_risk_signals_raise_the_score(self, engine, ordinary_payment, card_testing_payment):
        ordinary = engine.score(ordinary_payment).risk_score
        suspicious = engine.score(card_testing_payment).risk_score

        assert suspicious > ordinary

    def test_amount_alone_does_not_condemn_a_known_customer(self, engine, ordinary_payment):
        """
        The point of scaling the amount by the account's own norm. Without it the model would
        learn that wealthy customers are fraudulent, and the largest legitimate payments would
        be the most likely to be blocked.
        """
        wealthy = {
            **ordinary_payment,
            "amount_minor": 4_000_000,
            "avg_amount_minor": 3_800_000,
            "max_amount_minor": 9_000_000,
            "amount_sum_24h_minor": 3_800_000,
        }
        assert engine.score(wealthy).risk_score < 45


class TestFeatureContract:
    def test_publishes_every_feature_with_a_description(self):
        """
        Published so fraud-service's request builder and this service cannot drift apart
        unnoticed: a mismatch becomes a diff between two documents rather than a score that
        quietly looks wrong.
        """
        contract = feature_contract()

        assert contract["featureCount"] == len(FEATURE_NAMES)
        assert [item["name"] for item in contract["features"]] == list(FEATURE_NAMES)
        assert all(item["description"] for item in contract["features"])
