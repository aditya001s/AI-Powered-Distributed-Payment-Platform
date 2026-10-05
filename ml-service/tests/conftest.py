"""
Shared fixtures.

The trained model is loaded **once per session**. Loading it per test would add roughly a
second and a half each time — the sklearn warm-up cost measured in
:mod:`app.main` — and a suite slow enough to skip is a suite that gets skipped.

Tests that need a *freshly trained* model use :func:`tiny_model`, which trains on a few
thousand rows. That is the only way to check the training and persistence paths, and it is
marked ``slow`` so it can be excluded during a tight edit loop.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Iterator

import pytest

from app import config as config_module
from app.model import FraudModel, load_model, save, train
from app.scoring import ScoringEngine

REPO_MODEL_DIR = Path(__file__).resolve().parent.parent / "models"


@pytest.fixture(scope="session")
def trained_model() -> FraudModel:
    """The committed artifact, or a skip if the build step has not been run."""
    artifact = REPO_MODEL_DIR / "fraud_hgb.joblib"
    if not artifact.exists():
        pytest.skip(
            f"no model at {artifact}. Run `python -m app.train` first; the service "
            f"deliberately does not train on startup."
        )
    return load_model(artifact)


@pytest.fixture(scope="session")
def engine(trained_model: FraudModel) -> ScoringEngine:
    return ScoringEngine(trained_model)


@pytest.fixture(scope="session")
def tiny_model(tmp_path_factory: pytest.TempPathFactory) -> tuple[FraudModel, Path, Path]:
    """
    A model trained from scratch on a small dataset, plus where it was saved.

    Small enough to be quick, large enough that isotonic calibration has positives in every
    fold. Returns the paths as well so the round-trip through :func:`app.model.save` and
    :func:`app.model.load_model` can be checked.
    """
    model = train(rows=6_000, fraud_rate=0.05, seed=7, model_name="test_hgb")
    directory = tmp_path_factory.mktemp("model")
    artifact = directory / "test_hgb.joblib"
    metadata = directory / "test_hgb.metadata.json"
    save(model, artifact, metadata)
    return model, artifact, metadata


@pytest.fixture
def ordinary_payment() -> dict[str, Any]:
    """
    An unremarkable payment from an established customer.

    Used as the baseline that risk-raising variants are built from, so a test states only the
    fields it is actually about.
    """
    return {
        "amount_minor": 150_000,
        "currency": "INR",
        "payment_type": "P2P_TRANSFER",
        "hour_of_day": 14,
        "day_of_week": 3,
        "account_age_days": 900,
        "prior_payment_count": 420,
        "prior_chargeback_count": 0,
        "avg_amount_minor": 140_000,
        "max_amount_minor": 400_000,
        "payment_count_1h": 0,
        "payment_count_24h": 1,
        "amount_sum_24h_minor": 140_000,
        "failed_payment_count_24h": 0,
        "seconds_since_last_payment": 90_000,
        "is_new_device": False,
        "distinct_devices_30d": 1,
        "is_new_country": False,
        "distinct_countries_30d": 1,
        "country_code": "IN",
        "distance_from_last_km": 4.0,
        "has_geo": True,
        "is_new_payee": False,
        "payee_merchant_category": "5411",
    }


@pytest.fixture
def card_testing_payment(ordinary_payment: dict[str, Any]) -> dict[str, Any]:
    """
    Credential probing: a young account making many small attempts, several refused.

    Built from ``ordinary_payment`` so the test states only what it is about, and kept
    internally consistent — the prior 24-hour total matches the prior count, because a row that
    violated that invariant would sit outside anything the model trained on.
    """
    return {
        **ordinary_payment,
        "amount_minor": 900,
        "avg_amount_minor": None,
        "max_amount_minor": None,
        "account_age_days": 6,
        "prior_payment_count": 2,
        "payment_count_1h": 17,
        "payment_count_24h": 41,
        "amount_sum_24h_minor": 36_000,
        "failed_payment_count_24h": 12,
        "seconds_since_last_payment": 25,
        "is_new_device": True,
        "is_new_payee": True,
    }


@pytest.fixture
def mule_payment(ordinary_payment: dict[str, Any]) -> dict[str, Any]:
    """A cash-out to a new counterparty in an elevated-risk country."""
    return {
        **ordinary_payment,
        "amount_minor": 2_000_000,
        "payment_type": "WALLET_WITHDRAWAL",
        "country_code": "XX",
        "is_new_country": True,
        "distinct_countries_30d": 4,
        "is_new_payee": True,
        "payee_merchant_category": "6011",
        "payment_count_24h": 6,
        "amount_sum_24h_minor": 900_000,
    }


@pytest.fixture
def isolated_settings() -> Iterator[None]:
    """
    Restores the environment and the cached settings around a test that changes them.

    Without this, a test that sets ``ML_API_KEY`` would leak it into every test that ran
    afterwards — and the failure would surface somewhere unrelated.
    """
    saved = dict(os.environ)
    config_module.reset_settings()
    try:
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved)
        config_module.reset_settings()
