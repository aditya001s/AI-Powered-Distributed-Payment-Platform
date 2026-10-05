"""
The gradient-boosted fraud model: training, evaluation, persistence and loading.

**Why histogram gradient boosting.** Tabular fraud features are heterogeneous, unscaled and
full of interactions that matter only in combination — a new device is unremarkable, a new
device *plus* an amount eleven times the customer's norm *plus* 03:00 is not. Boosted trees
capture that without feature scaling, and
:class:`~sklearn.ensemble.HistGradientBoostingClassifier` is the histogram-based variant of
exactly the algorithm LightGBM implements. It is preferred here over LightGBM itself for
one specific reason beyond avoiding a native dependency: it treats ``NaN`` as a value with
its own learned split direction, and a large part of this feature set is legitimately
absent rather than zero.

**Why the probabilities are calibrated.** A raw boosting score is a ranking, not a
probability. The platform's thresholds are configured *as* probabilities
(``FRAUD_HIGH_RISK_THRESHOLD=0.80``), the risk score shown to an analyst is a probability in
percentage points, and both are meaningless if a score of 0.8 does not correspond to roughly
an eight-in-ten chance. :class:`~sklearn.calibration.CalibratedClassifierCV` with isotonic
regression fixes the mapping on held-out folds, and the training run reports the Brier score
and expected calibration error so the claim is checkable rather than assumed.

**Why training happens at build time.** The artifact is produced by ``python -m app.train``
and baked into the image. Training on startup would mean two replicas could serve different
models, so the same payment would get a different score depending on which pod answered —
and a disputed decision could not be reproduced at all.
"""

from __future__ import annotations

import hashlib
import json
import platform
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import sklearn
from joblib import dump, load
from sklearn.calibration import CalibratedClassifierCV
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import (
    average_precision_score,
    brier_score_loss,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold, train_test_split

from . import dataset as dataset_module
from .features import FEATURE_NAMES

#: Hyperparameters. Modest depth and a strong learning-rate/estimator trade-off, because
#: the honest constraint here is a ~2,000-row positive class: a deeper forest would fit the
#: generator's noise and the calibration curve would show it.
HYPERPARAMETERS: dict[str, Any] = {
    "max_iter": 300,
    "learning_rate": 0.06,
    "max_leaf_nodes": 31,
    "min_samples_leaf": 40,
    "l2_regularization": 1.0,
    "max_bins": 128,
    "early_stopping": True,
    "validation_fraction": 0.15,
    "n_iter_no_change": 25,
}

#: Folds used to calibrate. Three rather than five: each fold must hold enough positives
#: for isotonic regression to fit a stable step function.
CALIBRATION_FOLDS: int = 3

#: Share held back for evaluation, touched by neither fitting nor calibration.
TEST_SIZE: float = 0.25

#: A feature is ablated with ``NaN`` when it is absent this often in training, and with the
#: training median otherwise. See :meth:`FraudModel.ablation_row`.
NAN_ABLATION_THRESHOLD: float = 0.005

#: Rows in the fixed probe set used to fingerprint a trained model. See :func:`_fingerprint`.
PROBE_ROWS: int = 256
PROBE_SEED: int = 20_260_903


@dataclass(frozen=True)
class TrainingMetrics:
    """
    What the training run measured, on data used for neither fitting nor calibration.

    Read the ranking metrics with the caveat in :mod:`app.dataset` in mind: they describe
    recovery of an imposed structure, not real-world fraud detection.
    """

    rows: int
    fraud_rows: int
    observed_fraud_rate: float

    roc_auc: float
    average_precision: float

    #: Mean squared error of the probabilities. Lower is better; the base rate alone
    #: scores about ``p(1-p)``, so this is only meaningful next to that.
    brier_score: float
    baseline_brier_score: float

    #: Mean absolute gap between predicted probability and observed frequency, over ten
    #: equal-width bins. This is the number that says whether "0.8" means anything.
    expected_calibration_error: float

    #: Precision, recall and alert rate at each configured operating threshold. Alert rate
    #: is the share of *all* traffic that would be flagged, which is the figure that
    #: decides whether a fraud team can actually staff the queue.
    operating_points: dict[str, dict[str, float]] = field(default_factory=dict)

    #: Recall per fraud archetype at the blocking threshold.
    #:
    #: An aggregate recall figure hides which attacks the model is blind to, and "we catch
    #: 67% of fraud" is a very different statement from "we catch 90% of card testing and
    #: 30% of account takeover". This is the breakdown a fraud team needs in order to know
    #: what its rules still have to cover — and it is the only way to tell whether a
    #: single-archetype regression happened, because the aggregate barely moves.
    #:
    #: ``friendly_fraud`` is expected to sit near zero. Those rows are ordinary payments the
    #: real customer charged back, so no model can identify them and a number above zero
    #: would mean the generator had leaked a tell.
    archetype_recall: dict[str, dict[str, float]] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {
            "rows": self.rows,
            "fraudRows": self.fraud_rows,
            "observedFraudRate": round(self.observed_fraud_rate, 6),
            "rocAuc": round(self.roc_auc, 6),
            "averagePrecision": round(self.average_precision, 6),
            "brierScore": round(self.brier_score, 6),
            "baselineBrierScore": round(self.baseline_brier_score, 6),
            "expectedCalibrationError": round(self.expected_calibration_error, 6),
            "operatingPoints": {
                threshold: {key: round(value, 6) for key, value in point.items()}
                for threshold, point in self.operating_points.items()
            },
            "archetypeRecall": {
                archetype: {key: round(value, 6) for key, value in stats.items()}
                for archetype, stats in self.archetype_recall.items()
            },
        }


@dataclass(frozen=True)
class FraudModel:
    """
    A trained, calibrated model plus everything needed to use it safely.

    ``feature_names`` travels with the model on purpose. It is the guard against a future
    edit to :data:`app.features.FEATURE_NAMES` silently reinterpreting an existing
    artifact: a boosted tree indexes columns positionally, so a reordered contract does not
    fail, it just returns wrong answers.
    """

    estimator: Any
    feature_names: tuple[str, ...]

    #: Per-feature substitute used by the explanation. Either ``NaN`` or a training median.
    ablation_values: np.ndarray

    version: str
    trained_at: str
    metrics: TrainingMetrics
    provenance: dict[str, Any]

    def predict_proba(self, matrix: np.ndarray) -> np.ndarray:
        """Calibrated fraud probability for each row."""
        if matrix.ndim != 2 or matrix.shape[1] != len(self.feature_names):
            raise ValueError(
                f"expected a matrix with {len(self.feature_names)} columns in the order "
                f"this model was trained with, got shape {matrix.shape}"
            )
        return self.estimator.predict_proba(matrix)[:, 1]

    def ablation_row(self, row: np.ndarray) -> np.ndarray:
        """
        Builds the ``(1 + n_features, n_features)`` matrix the explanation scores in one call.

        Row 0 is the payment as it actually is. Row ``i + 1`` is the same payment with
        feature ``i`` replaced by its substitute, so the difference in score is what that
        one feature contributed.

        The substitute is ``NaN`` — literally "we do not know this" — for features that are
        genuinely absent often enough in training for the model to have learned a direction
        for it. For features that are never absent, ``NaN`` would send the row down an
        untrained path and produce a meaningless number, so the training median is used
        instead: "a typical value" rather than "unknown".
        """
        count = len(self.feature_names)
        matrix = np.repeat(row.reshape(1, -1), count + 1, axis=0)
        for index in range(count):
            matrix[index + 1, index] = self.ablation_values[index]
        return matrix

    def to_metadata(self) -> dict[str, Any]:
        """Everything a human or a dashboard needs, without unpickling anything."""
        return {
            "modelVersion": self.version,
            "trainedAt": self.trained_at,
            "algorithm": "HistGradientBoostingClassifier",
            "calibration": "isotonic",
            "featureCount": len(self.feature_names),
            "featureNames": list(self.feature_names),
            "hyperparameters": HYPERPARAMETERS,
            "metrics": self.metrics.to_dict(),
            "provenance": self.provenance,
            "dataCaveat": (
                "Trained on SYNTHETIC data with an imposed causal structure. The ranking "
                "metrics measure recovery of that structure, not real-world fraud "
                "detection performance, and must not be quoted as the latter."
            ),
        }


def _expected_calibration_error(labels: np.ndarray, probabilities: np.ndarray,
                                bins: int = 10) -> float:
    """
    Mean absolute gap between predicted probability and observed frequency.

    Weighted by bin population, so a bin holding three samples cannot dominate the figure.
    """
    edges = np.linspace(0.0, 1.0, bins + 1)
    total = 0.0
    for lower, upper in zip(edges[:-1], edges[1:]):
        in_bin = (probabilities > lower) & (probabilities <= upper)
        if lower == 0.0:
            in_bin |= probabilities == 0.0
        population = int(in_bin.sum())
        if population == 0:
            continue
        gap = abs(float(probabilities[in_bin].mean()) - float(labels[in_bin].mean()))
        total += gap * population
    return total / len(labels)


def _operating_points(labels: np.ndarray, probabilities: np.ndarray,
                      thresholds: Sequence[float]) -> dict[str, dict[str, float]]:
    points: dict[str, dict[str, float]] = {}
    for threshold in thresholds:
        flagged = probabilities >= threshold
        points[f"{threshold:.2f}"] = {
            "threshold": float(threshold),
            "precision": float(precision_score(labels, flagged, zero_division=0.0)),
            "recall": float(recall_score(labels, flagged, zero_division=0.0)),
            # The share of ALL traffic flagged. A recall figure with no alert rate beside it
            # hides whether the queue is staffable.
            "alertRate": float(flagged.mean()),
            "flagged": float(flagged.sum()),
        }
    return points


def _archetype_recall(archetypes: Sequence[str | None], labels: np.ndarray,
                      probabilities: np.ndarray, threshold: float) -> dict[str, dict[str, float]]:
    """
    Recall for each fraud archetype among the held-out positives.

    Restricted to rows actually labelled fraudulent, so the unreported-fraud rows — which
    carry a clean label by construction — are correctly excluded rather than counted as
    misses.
    """
    grouped: dict[str, list[bool]] = {}
    for archetype, label, probability in zip(archetypes, labels, probabilities):
        if label != 1 or archetype is None:
            continue
        grouped.setdefault(archetype, []).append(bool(probability >= threshold))

    return {
        archetype: {
            "threshold": float(threshold),
            "recall": float(sum(hits) / len(hits)),
            "positives": float(len(hits)),
        }
        for archetype, hits in sorted(grouped.items())
    }


def _fingerprint(estimator: Any, ablation_values: np.ndarray) -> str:
    """
    A behavioural fingerprint of the trained model.

    Hashes the model's own predictions over a fixed probe matrix rather than hashing
    hyperparameters or bytes. That gives the version exactly the semantics wanted: it
    changes when and only when the model's *behaviour* changes — a different dataset, a
    different scikit-learn build, a changed feature derivation — and stays stable across
    rebuilds that alter nothing observable. Hashing a timestamp instead would make two
    identical models look different; hashing hyperparameters would make two different
    models look the same.
    """
    rng = np.random.default_rng(PROBE_SEED)
    probe = rng.normal(0.0, 1.0, size=(PROBE_ROWS, len(FEATURE_NAMES)))
    # Exercise the NaN paths too, so a change in missing-value handling is detected.
    probe[rng.random(probe.shape) < 0.1] = np.nan

    probabilities = estimator.predict_proba(probe)[:, 1]
    digest = hashlib.sha256()
    digest.update(np.round(probabilities, 9).tobytes())
    digest.update(np.round(np.nan_to_num(ablation_values, nan=-1.0), 9).tobytes())
    digest.update("|".join(FEATURE_NAMES).encode("utf-8"))
    return digest.hexdigest()[:12]


def train(rows: int, fraud_rate: float, seed: int, model_name: str,
          thresholds: Sequence[float] = (0.45, 0.80)) -> FraudModel:
    """
    Generates data, fits the model, calibrates it and measures it.

    The evaluation split is held back from both fitting and calibration. Reporting metrics
    on data the calibrator saw would make the calibration error look far better than it is,
    which is the one number here that has to be trustworthy.
    """
    data = dataset_module.generate(rows=rows, fraud_rate=fraud_rate, seed=seed)

    # Split indices rather than arrays, so the archetype of each held-out row stays known
    # and per-attack recall can be measured. Splitting the arrays directly would discard
    # exactly the label needed to answer "which attacks does this miss".
    train_index, test_index = train_test_split(
        np.arange(data.features.shape[0]),
        test_size=TEST_SIZE,
        random_state=seed,
        # Stratified, or a 1.7% positive class could land almost entirely on one side.
        stratify=data.labels,
    )
    train_features = data.features[train_index]
    test_features = data.features[test_index]
    train_labels = data.labels[train_index]
    test_labels = data.labels[test_index]

    base = HistGradientBoostingClassifier(random_state=seed, **HYPERPARAMETERS)
    calibrated = CalibratedClassifierCV(
        estimator=base,
        method="isotonic",
        cv=StratifiedKFold(n_splits=CALIBRATION_FOLDS, shuffle=True, random_state=seed),
    )
    calibrated.fit(train_features, train_labels)

    probabilities = calibrated.predict_proba(test_features)[:, 1]

    base_rate = float(train_labels.mean())
    metrics = TrainingMetrics(
        rows=int(data.features.shape[0]),
        fraud_rows=data.fraud_count,
        observed_fraud_rate=data.observed_fraud_rate,
        roc_auc=float(roc_auc_score(test_labels, probabilities)),
        average_precision=float(average_precision_score(test_labels, probabilities)),
        brier_score=float(brier_score_loss(test_labels, probabilities)),
        # What you would score by predicting the base rate for everything. Without this,
        # a Brier score of 0.015 looks excellent when it may be no better than a constant.
        baseline_brier_score=float(
            brier_score_loss(test_labels, np.full_like(probabilities, base_rate))
        ),
        expected_calibration_error=_expected_calibration_error(test_labels, probabilities),
        operating_points=_operating_points(test_labels, probabilities, thresholds),
        archetype_recall=_archetype_recall(
            [data.archetypes[index] for index in test_index],
            test_labels,
            probabilities,
            max(thresholds),
        ),
    )

    # Ablation substitutes, measured on the training split only.
    nan_fraction = np.isnan(train_features).mean(axis=0)
    medians = np.nanmedian(train_features, axis=0)
    medians = np.nan_to_num(medians, nan=0.0)
    ablation_values = np.where(nan_fraction >= NAN_ABLATION_THRESHOLD, np.nan, medians)

    version = f"{model_name}-{_fingerprint(calibrated, ablation_values)}"

    return FraudModel(
        estimator=calibrated,
        feature_names=FEATURE_NAMES,
        ablation_values=ablation_values,
        version=version,
        trained_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        metrics=metrics,
        provenance={
            "trainingRows": rows,
            "requestedFraudRate": fraud_rate,
            "randomSeed": seed,
            "testSize": TEST_SIZE,
            "calibrationFolds": CALIBRATION_FOLDS,
            "sklearnVersion": sklearn.__version__,
            "numpyVersion": np.__version__,
            "pythonVersion": platform.python_version(),
            "dataSource": "synthetic",
        },
    )


def save(model: FraudModel, artifact_path: Path, metadata_path: Path) -> None:
    """
    Writes the artifact and its metadata side by side.

    The metadata is duplicated outside the pickle deliberately: the metrics and the
    provenance should be readable, greppable and reviewable in a diff without importing
    scikit-learn or trusting a pickle enough to load it.
    """
    artifact_path.parent.mkdir(parents=True, exist_ok=True)
    dump(
        {
            "estimator": model.estimator,
            "feature_names": list(model.feature_names),
            "ablation_values": model.ablation_values,
            "version": model.version,
            "trained_at": model.trained_at,
            "metrics": model.metrics.to_dict(),
            "provenance": model.provenance,
        },
        artifact_path,
        compress=3,
    )
    metadata_path.write_text(
        json.dumps(model.to_metadata(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def load_model(artifact_path: Path) -> FraudModel:
    """
    Loads a trained model, refusing one whose feature contract no longer matches.

    The check is the point. A model trained against a different column order still
    *predicts* — it just predicts using the wrong feature for every split. Refusing to
    start is the only safe response, because there is no symptom to notice otherwise.
    """
    if not artifact_path.exists():
        raise FileNotFoundError(
            f"no model artifact at {artifact_path}. Run `python -m app.train` — the "
            f"service deliberately does not train on startup, so that every replica "
            f"serves identical predictions."
        )

    payload = load(artifact_path)
    stored_names = tuple(payload["feature_names"])
    if stored_names != FEATURE_NAMES:
        raise ValueError(
            "the stored model's feature contract does not match this build.\n"
            f"  stored:  {stored_names}\n"
            f"  current: {FEATURE_NAMES}\n"
            "A boosted tree indexes columns positionally, so serving this model would "
            "apply every split to the wrong feature without failing. Retrain instead."
        )

    metrics_dict = payload.get("metrics", {})
    metrics = TrainingMetrics(
        rows=int(metrics_dict.get("rows", 0)),
        fraud_rows=int(metrics_dict.get("fraudRows", 0)),
        observed_fraud_rate=float(metrics_dict.get("observedFraudRate", 0.0)),
        roc_auc=float(metrics_dict.get("rocAuc", 0.0)),
        average_precision=float(metrics_dict.get("averagePrecision", 0.0)),
        brier_score=float(metrics_dict.get("brierScore", 0.0)),
        baseline_brier_score=float(metrics_dict.get("baselineBrierScore", 0.0)),
        expected_calibration_error=float(metrics_dict.get("expectedCalibrationError", 0.0)),
        operating_points=metrics_dict.get("operatingPoints", {}),
        archetype_recall=metrics_dict.get("archetypeRecall", {}),
    )

    return FraudModel(
        estimator=payload["estimator"],
        feature_names=stored_names,
        ablation_values=np.asarray(payload["ablation_values"], dtype=np.float64),
        version=str(payload["version"]),
        trained_at=str(payload["trained_at"]),
        metrics=metrics,
        provenance=payload.get("provenance", {}),
    )
