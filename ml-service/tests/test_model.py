"""
Tests for training, calibration, persistence and the version fingerprint.

The load-time guards get the most attention here, because they protect against the failure
mode this whole design is arranged around: a model that keeps returning plausible numbers
while being wrong.
"""

from __future__ import annotations

import json

import numpy as np
import pytest

from app.features import FEATURE_NAMES
from app.model import (
    NAN_ABLATION_THRESHOLD,
    _expected_calibration_error,
    load_model,
    save,
    train,
)

pytestmark = pytest.mark.slow


class TestTraining:
    def test_learns_something(self, tiny_model):
        """
        A floor, not a target.

        Deliberately loose: this asserts the pipeline learns at all. The real numbers are
        recorded in the artifact's metadata and quoted in the README, where the synthetic-data
        caveat is next to them.
        """
        model, _, _ = tiny_model

        assert model.metrics.roc_auc > 0.75
        assert model.metrics.average_precision > 0.3

    def test_beats_predicting_the_base_rate(self, tiny_model):
        """
        The comparison that makes a Brier score mean anything.

        0.005 sounds excellent until you notice that always answering "1.7%" scores 0.017. A
        model that failed this would be worse than a constant while looking accurate.
        """
        model, _, _ = tiny_model
        assert model.metrics.brier_score < model.metrics.baseline_brier_score

    def test_probabilities_are_calibrated(self, tiny_model):
        """
        The one metric that has to be trustworthy.

        Every threshold in the platform is expressed as a probability, so if 0.8 does not mean
        roughly eight-in-ten then FRAUD_HIGH_RISK_THRESHOLD is a meaningless dial.
        """
        model, _, _ = tiny_model
        assert model.metrics.expected_calibration_error < 0.05

    def test_reports_recall_for_each_attack_type(self, tiny_model):
        """
        An aggregate recall figure hides which attacks the model is blind to, and that is what
        the rule engine has to cover.
        """
        model, _, _ = tiny_model

        assert model.metrics.archetype_recall, "per-archetype recall must be measured"
        for stats in model.metrics.archetype_recall.values():
            assert 0.0 <= stats["recall"] <= 1.0
            assert stats["positives"] > 0

    def test_operating_points_include_an_alert_rate(self, tiny_model):
        """
        Recall without an alert rate hides whether the queue is staffable: 90% recall at a 40%
        alert rate is not a fraud system, it is a manual review department.
        """
        model, _, _ = tiny_model

        for point in model.metrics.operating_points.values():
            assert {"threshold", "precision", "recall", "alertRate", "flagged"} <= set(point)
            assert 0.0 <= point["alertRate"] <= 1.0

    def test_training_is_reproducible(self):
        """
        Same seed, same model, same version. This is what lets a disputed decision be
        reproduced from the model version recorded on the assessment.
        """
        first = train(rows=3_000, fraud_rate=0.06, seed=99, model_name="repro")
        second = train(rows=3_000, fraud_rate=0.06, seed=99, model_name="repro")

        assert first.version == second.version
        assert first.metrics.roc_auc == second.metrics.roc_auc


class TestVersioning:
    def test_the_version_is_a_behavioural_fingerprint(self):
        """
        The version changes when and only when predictions change.

        Hashing a timestamp would make two identical models look different; hashing
        hyperparameters would make two behaviourally different models look the same. Both
        break the audit trail in opposite directions.
        """
        baseline = train(rows=3_000, fraud_rate=0.06, seed=99, model_name="fp")
        different_data = train(rows=3_000, fraud_rate=0.06, seed=100, model_name="fp")

        assert baseline.version != different_data.version
        assert baseline.version.startswith("fp-")

    def test_the_name_prefixes_the_version(self, tiny_model):
        model, _, _ = tiny_model
        assert model.version.startswith("test_hgb-")


class TestPersistence:
    def test_round_trips_without_changing_predictions(self, tiny_model):
        """
        A reload that scored differently would mean every restart silently retuned the fraud
        system.
        """
        model, artifact, _ = tiny_model
        reloaded = load_model(artifact)

        probe = np.random.default_rng(1).normal(size=(64, len(FEATURE_NAMES)))
        assert np.allclose(model.predict_proba(probe), reloaded.predict_proba(probe))
        assert reloaded.version == model.version

    def test_metadata_is_readable_without_unpickling(self, tiny_model):
        """
        Written as plain JSON on purpose: the metrics should be greppable and reviewable in a
        diff without importing scikit-learn or trusting a pickle enough to load it.
        """
        model, _, metadata_path = tiny_model
        document = json.loads(metadata_path.read_text(encoding="utf-8"))

        assert document["modelVersion"] == model.version
        assert document["algorithm"] == "HistGradientBoostingClassifier"
        assert document["calibration"] == "isotonic"
        assert document["featureNames"] == list(FEATURE_NAMES)
        assert document["metrics"]["rocAuc"] == pytest.approx(model.metrics.roc_auc)

    def test_metadata_carries_the_synthetic_data_caveat(self, tiny_model):
        """
        The caveat travels with the metrics, so a number cannot be quoted without it.
        """
        _, _, metadata_path = tiny_model
        document = json.loads(metadata_path.read_text(encoding="utf-8"))

        assert "SYNTHETIC" in document["dataCaveat"]
        assert document["provenance"]["dataSource"] == "synthetic"

    def test_a_missing_artifact_says_what_to_do(self, tmp_path):
        """
        The message has to name the command, because the service deliberately does not train
        on startup and the absence is otherwise mystifying.
        """
        with pytest.raises(FileNotFoundError, match="app.train"):
            load_model(tmp_path / "nothing.joblib")

    def test_refuses_a_model_whose_feature_contract_changed(self, tiny_model, tmp_path):
        """
        **The most important guard in the file.**

        A model trained against a different column order still predicts — it just applies every
        split to the wrong feature. There is no symptom to notice, so refusing to load is the
        only safe response.
        """
        import joblib

        model, artifact, _ = tiny_model
        payload = joblib.load(artifact)
        payload["feature_names"] = list(reversed(payload["feature_names"]))

        tampered = tmp_path / "reordered.joblib"
        joblib.dump(payload, tampered)

        with pytest.raises(ValueError, match="feature contract"):
            load_model(tampered)


class TestAblationSubstitutes:
    def test_absent_features_ablate_to_unknown(self, tiny_model):
        """
        For a feature that is genuinely absent sometimes, "we do not know it" is the honest
        substitute and the model has a trained path for it.
        """
        model, _, _ = tiny_model
        index = model.feature_names.index("avg_amount_minor")
        assert np.isnan(model.ablation_values[index])

    def test_always_present_features_ablate_to_a_typical_value(self, tiny_model):
        """
        For a feature that is never absent, NaN would send the row down an untrained path and
        the resulting contribution would be a meaningless number presented as a reason a
        payment was held.
        """
        model, _, _ = tiny_model
        index = model.feature_names.index("amount_minor")
        assert not np.isnan(model.ablation_values[index])

    def test_the_matrix_holds_the_actual_row_first(self, tiny_model):
        model, _, _ = tiny_model
        row = np.arange(len(FEATURE_NAMES), dtype=np.float64)

        matrix = model.ablation_row(row)

        assert matrix.shape == (len(FEATURE_NAMES) + 1, len(FEATURE_NAMES))
        assert np.array_equal(matrix[0], row)
        # Row i+1 differs from the original in exactly column i and nowhere else.
        for index in range(len(FEATURE_NAMES)):
            differences = ~np.isclose(matrix[index + 1], row, equal_nan=True)
            assert differences.sum() <= 1
            if differences.any():
                assert differences[index]

    def test_the_threshold_is_a_small_positive_share(self):
        assert 0.0 < NAN_ABLATION_THRESHOLD < 0.1


class TestCalibrationError:
    def test_a_perfect_forecaster_scores_zero(self):
        labels = np.array([0, 0, 1, 1])
        assert _expected_calibration_error(labels, np.array([0.0, 0.0, 1.0, 1.0])) == 0.0

    def test_a_confidently_wrong_forecaster_scores_one(self):
        labels = np.array([0, 0, 1, 1])
        assert _expected_calibration_error(labels, np.array([1.0, 1.0, 0.0, 0.0])) == pytest.approx(1.0)

    def test_bins_are_weighted_by_population(self):
        """
        Otherwise a bin holding three samples would count as much as one holding thirty
        thousand, and the headline figure could be dominated by noise.
        """
        labels = np.concatenate([np.zeros(999), np.ones(1)])
        probabilities = np.concatenate([np.zeros(999), np.array([0.0])])
        # 999 perfect predictions and one badly wrong: the error must stay near zero.
        assert _expected_calibration_error(labels, probabilities) < 0.01

    def test_shape_mismatch_is_rejected_by_numpy(self):
        with pytest.raises(Exception):
            _expected_calibration_error(np.array([0, 1]), np.array([0.5]))
