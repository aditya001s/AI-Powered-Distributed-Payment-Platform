"""
Training entry point: ``python -m app.train``.

Run at image build time, not at startup. Two reasons, and the second is the one that
matters: training takes seconds that a readiness probe should not wait for, and — more
importantly — a model trained per replica means two pods disagree about the same payment.
Baking the artifact into the image makes every replica byte-identical, so a score can be
reproduced from the model version recorded on the assessment.

Prints the metrics it measured. Read them with the caveat in :mod:`app.dataset` in mind: the
data is synthetic with an imposed structure, so the ranking figures describe recovery of
that structure and are not a claim about real-world fraud detection.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .config import settings
from .model import save, train


def _thresholds(raw: str) -> tuple[float, ...]:
    values = tuple(float(part) for part in raw.split(",") if part.strip())
    if not values:
        raise argparse.ArgumentTypeError("at least one threshold is required")
    for value in values:
        if not 0.0 < value < 1.0:
            raise argparse.ArgumentTypeError(f"threshold {value} must be in (0,1)")
    return values


def main(argv: list[str] | None = None) -> int:
    config = settings()

    parser = argparse.ArgumentParser(
        prog="python -m app.train",
        description="Trains and calibrates the AegisPay fraud model.",
    )
    parser.add_argument("--rows", type=int, default=config.training_rows,
                        help="training rows to generate (env: ML_TRAINING_ROWS)")
    parser.add_argument("--fraud-rate", type=float, default=config.fraud_rate,
                        help="share labelled fraudulent (env: ML_FRAUD_RATE)")
    parser.add_argument("--seed", type=int, default=config.random_seed,
                        help="fixed so a rebuild reproduces the model (env: ML_RANDOM_SEED)")
    parser.add_argument("--model-dir", type=Path, default=config.model_dir,
                        help="where to write the artifact (env: MODEL_DIR)")
    parser.add_argument("--model-name", type=str, default=config.model_name,
                        help="artifact base name (env: MODEL_NAME)")
    parser.add_argument(
        "--thresholds", type=_thresholds, default=(0.45, 0.80),
        help="operating points to report precision/recall/alert-rate at. Defaults match "
             "FRAUD_MEDIUM_RISK_THRESHOLD and FRAUD_HIGH_RISK_THRESHOLD.",
    )
    args = parser.parse_args(argv)

    print(f"Generating {args.rows:,} synthetic rows at a {args.fraud_rate:.3%} fraud rate "
          f"(seed {args.seed})...", flush=True)

    model = train(
        rows=args.rows,
        fraud_rate=args.fraud_rate,
        seed=args.seed,
        model_name=args.model_name,
        thresholds=args.thresholds,
    )

    artifact_path = args.model_dir / f"{args.model_name}.joblib"
    metadata_path = args.model_dir / f"{args.model_name}.metadata.json"
    save(model, artifact_path, metadata_path)

    metrics = model.metrics
    print()
    print(f"Model version        {model.version}")
    print(f"Trained at           {model.trained_at}")
    print(f"Rows / fraud rows    {metrics.rows:,} / {metrics.fraud_rows:,} "
          f"({metrics.observed_fraud_rate:.3%})")
    print()
    print("Held-out evaluation (used for neither fitting nor calibration)")
    print(f"  ROC AUC            {metrics.roc_auc:.4f}")
    print(f"  Average precision  {metrics.average_precision:.4f}")
    print(f"  Brier score        {metrics.brier_score:.5f}  "
          f"(base-rate baseline {metrics.baseline_brier_score:.5f})")
    print(f"  Calibration error  {metrics.expected_calibration_error:.5f}")
    print()
    print("Operating points")
    print(f"  {'threshold':>9}  {'precision':>9}  {'recall':>7}  {'alert rate':>10}  {'flagged':>7}")
    for label, point in sorted(metrics.operating_points.items()):
        print(f"  {label:>9}  {point['precision']:>9.4f}  {point['recall']:>7.4f}  "
              f"{point['alertRate']:>10.4%}  {int(point['flagged']):>7,}")
    print()
    if metrics.archetype_recall:
        blocking = max(args.thresholds)
        print(f"Recall per attack type at {blocking:.2f} "
              f"(aggregate recall hides which attacks are missed)")
        print(f"  {'archetype':<22}  {'recall':>7}  {'positives':>9}")
        for archetype, stats in sorted(metrics.archetype_recall.items()):
            print(f"  {archetype:<22}  {stats['recall']:>7.4f}  {int(stats['positives']):>9,}")
        print()
        print("  friendly_fraud near zero is CORRECT: those are ordinary payments the real")
        print("  customer charged back, so nothing distinguishes them.")
        print()
    print(f"Artifact             {artifact_path}")
    print(f"Metadata             {metadata_path}")
    print()
    print("NOTE: synthetic training data with an imposed causal structure. These figures")
    print("      measure recovery of that structure, NOT real-world fraud performance.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
