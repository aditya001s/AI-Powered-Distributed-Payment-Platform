"""
Tests for the HTTP layer.

Uses Starlette's ``TestClient``, which runs the real application through the real ASGI stack:
routing, pydantic validation, the dependency graph, the exception handlers and the lifespan.
Calling the endpoint functions directly would skip exactly the parts this file is about.
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient

from app import config as config_module
from app import main as main_module

REPO_MODEL = (
    __import__("pathlib").Path(__file__).resolve().parent.parent / "models" / "fraud_hgb.joblib"
)


@pytest.fixture
def client(isolated_settings) -> TestClient:
    """
    A client against the real app, with a model loaded and an API key required.

    The lifespan runs, so this also exercises the startup path: loading the artifact, warming
    it, and failing fast if no key is configured outside local development.
    """
    if not REPO_MODEL.exists():
        pytest.skip(f"no model at {REPO_MODEL}; run `python -m app.train` first")

    os.environ["ML_API_KEY"] = "test-secret-key"
    os.environ["PLATFORM_ENV"] = "test"
    config_module.reset_settings()

    with TestClient(main_module.app) as test_client:
        yield test_client


@pytest.fixture
def unready_client(isolated_settings, tmp_path) -> TestClient:
    """
    A client whose model artifact does not exist.

    Points ``MODEL_DIR`` at an empty directory rather than deleting anything, so the
    "no model" path is reachable without touching the real artifact.
    """
    os.environ["ML_API_KEY"] = "test-secret-key"
    os.environ["PLATFORM_ENV"] = "test"
    os.environ["MODEL_DIR"] = str(tmp_path)
    config_module.reset_settings()

    with TestClient(main_module.app) as test_client:
        yield test_client


def _headers() -> dict[str, str]:
    return {"X-API-Key": "test-secret-key"}


class TestScoring:
    def test_scores_a_payment(self, client, ordinary_payment):
        response = client.post("/score", json=_camel(ordinary_payment), headers=_headers())

        assert response.status_code == 200
        body = response.json()
        assert 0 <= body["riskScore"] <= 100
        assert 0.0 <= body["fraudProbability"] <= 1.0
        assert body["modelVersion"].startswith("fraud_hgb-")
        assert body["features"]
        assert "scoringLatencyMs" in body

    def test_the_wire_format_is_camel_case(self, client, ordinary_payment):
        """
        fraud-service is a Spring application serialising Java records, so the contract is
        camelCase in both directions. A mismatch here would be silent: an unrecognised field
        would be ignored and the score computed from a partial vector.
        """
        response = client.post("/score", json=_camel(ordinary_payment), headers=_headers())
        body = response.json()

        for key in ("fraudProbability", "riskScore", "modelVersion", "scoringLatencyMs"):
            assert key in body
        # Feature *keys* stay snake_case: they are data, not field names.
        assert "amount_minor" in body["features"]

    def test_rejects_an_unknown_field(self, client, ordinary_payment):
        """
        ``extra="forbid"`` is the point. A misspelled field silently ignored would arrive as
        None, the feature would be absent, and nothing would report a problem.
        """
        payload = {**_camel(ordinary_payment), "amountMinorr": 123}
        response = client.post("/score", json=payload, headers=_headers())

        assert response.status_code == 422
        assert response.json()["code"] == "VALIDATION_FAILED"
        assert "amountMinorr" in response.json()["message"]

    def test_requires_an_amount(self, client):
        response = client.post("/score", json={"currency": "INR"}, headers=_headers())
        assert response.status_code == 422

    def test_rejects_a_negative_amount(self, client, ordinary_payment):
        payload = {**_camel(ordinary_payment), "amountMinor": -1}
        assert client.post("/score", json=payload, headers=_headers()).status_code == 422

    def test_accepts_a_request_with_only_an_amount(self, client):
        """
        Everything except the amount is genuinely optional: a brand-new account has no history
        and an API-channel payment has no device.
        """
        response = client.post("/score", json={"amountMinor": 250_000}, headers=_headers())

        assert response.status_code == 200
        assert response.json()["features"]["avg_amount_minor"] is None

    def test_absent_values_serialise_as_null_not_zero(self, client):
        """
        Zero would invert the meaning during a dispute: "distance_from_last_km: 0" reads as
        "same location as last time" when the truth is that no location was available.
        """
        body = client.post("/score", json={"amountMinor": 250_000}, headers=_headers()).json()
        assert body["features"]["distance_from_last_km"] is None

    def test_explains_a_high_scoring_payment(self, client, card_testing_payment):
        body = client.post("/score", json=_camel(card_testing_payment),
                           headers=_headers()).json()

        assert body["riskScore"] > 45
        assert body["contributions"]
        first = body["contributions"][0]
        assert set(first) == {"feature", "description", "value", "impactPoints", "direction"}

    def test_returns_no_decision_and_no_risk_level(self, client, ordinary_payment):
        """
        The boundary this service is built around. Bucketing a probability and choosing
        ALLOW / REVIEW / BLOCK needs the deterministic rules too, and only fraud-service has
        both — so putting a threshold here would mean redeploying two services to retune one
        number.
        """
        body = client.post("/score", json=_camel(ordinary_payment), headers=_headers()).json()

        assert "decision" not in body
        assert "riskLevel" not in body


class TestAuthentication:
    def test_rejects_a_request_with_no_key(self, client, ordinary_payment):
        response = client.post("/score", json=_camel(ordinary_payment))

        assert response.status_code == 401
        assert response.json()["code"] == "UNAUTHORIZED"

    def test_rejects_a_wrong_key(self, client, ordinary_payment):
        response = client.post("/score", json=_camel(ordinary_payment),
                               headers={"X-API-Key": "not-the-key"})
        assert response.status_code == 401

    def test_the_rejection_does_not_confirm_the_shape_of_the_secret(self, client,
                                                                   ordinary_payment):
        """
        Both failures return the same body. Telling an attacker their guess had the right
        length or prefix is a free hint.
        """
        missing = client.post("/score", json=_camel(ordinary_payment))
        wrong = client.post("/score", json=_camel(ordinary_payment),
                            headers={"X-API-Key": "x"})

        assert missing.json()["message"] == wrong.json()["message"]

    def test_the_model_endpoint_is_protected_too(self, client):
        """
        Model metadata includes the operating thresholds' measured precision and recall, which
        is reconnaissance for choosing an amount that scores below the boundary.
        """
        assert client.get("/model").status_code == 401
        assert client.get("/model", headers=_headers()).status_code == 200

    def test_metrics_and_health_stay_open(self, client):
        """
        Scrape and probe endpoints are unauthenticated like every other service's, because they
        expose counters and a score histogram, never a payment or a feature value.
        """
        assert client.get("/metrics").status_code == 200
        assert client.get("/health").status_code == 200
        assert client.get("/health/readiness").status_code == 200


class TestReadiness:
    def test_liveness_is_up_without_a_model(self, unready_client):
        """
        Deliberately independent of the model. A liveness probe that failed here would restart
        the container in a loop and destroy the logs explaining why the model is missing.
        """
        response = unready_client.get("/health")

        assert response.status_code == 200
        assert response.json()["status"] == "UP"
        assert response.json()["modelLoaded"] is False

    def test_readiness_fails_without_a_model(self, unready_client):
        """
        No model means this instance must receive no traffic. Scoring anyway — or training on
        boot — would let two replicas disagree about the same payment.
        """
        response = unready_client.get("/health/readiness")

        assert response.status_code == 503
        assert response.json()["code"] == "MODEL_UNAVAILABLE"

    def test_scoring_returns_503_without_a_model(self, unready_client, ordinary_payment):
        response = unready_client.post("/score", json=_camel(ordinary_payment),
                                       headers=_headers())

        assert response.status_code == 503
        assert response.json()["code"] == "MODEL_UNAVAILABLE"
        # The reason names the missing artifact and the command that produces it.
        assert "app.train" in response.json()["message"]

    def test_readiness_reports_the_model_version_when_loaded(self, client):
        body = client.get("/health/readiness").json()

        assert body["modelLoaded"] is True
        assert body["modelVersion"].startswith("fraud_hgb-")


class TestModelEndpoint:
    def test_publishes_provenance_metrics_and_the_feature_contract(self, client):
        body = client.get("/model", headers=_headers()).json()

        assert body["algorithm"] == "HistGradientBoostingClassifier"
        assert body["calibration"] == "isotonic"
        assert body["featureCount"] == body["featureContract"]["featureCount"]
        assert body["metrics"]["rocAuc"] > 0.75
        assert body["metrics"]["archetypeRecall"]

    def test_carries_the_synthetic_data_caveat(self, client):
        """
        The caveat travels with the metrics so a figure cannot be quoted without it.
        """
        body = client.get("/model", headers=_headers()).json()

        assert "SYNTHETIC" in body["dataCaveat"]
        assert body["provenance"]["dataSource"] == "synthetic"

    def test_records_the_library_versions_that_produced_the_model(self, client):
        """
        A gradient-boosted model is serialised against the scikit-learn build that made it, so
        the version that produced a decision is part of reproducing it.
        """
        provenance = client.get("/model", headers=_headers()).json()["provenance"]

        assert provenance["sklearnVersion"]
        assert provenance["randomSeed"] == 42


class TestObservability:
    def test_correlation_id_is_echoed(self, client, ordinary_payment):
        """
        The platform propagates a correlation ID across the gateway, Kafka and four Java
        services. A score that dropped it would be untraceable back to its payment.
        """
        response = client.post(
            "/score",
            json=_camel(ordinary_payment),
            headers={**_headers(), "X-Correlation-Id": "corr-abc-123"},
        )

        assert response.headers["X-Correlation-Id"] == "corr-abc-123"

    def test_errors_carry_the_correlation_id_too(self, client):
        """
        Especially errors: a failure is exactly when someone needs to find the request.
        """
        response = client.post(
            "/score",
            json={"nonsense": 1},
            headers={**_headers(), "X-Correlation-Id": "corr-err-9"},
        )

        assert response.status_code == 422
        assert response.json()["correlationId"] == "corr-err-9"

    def test_errors_use_the_platform_shape(self, client):
        """
        Field for field the same as ``com.aegispay.common.api.ApiError``, so fraud-service
        parses a failure here with the code it already has rather than a Python special case.
        """
        body = client.post("/score", json={"nonsense": 1}, headers=_headers()).json()

        assert set(body) == {"timestamp", "status", "code", "message", "path", "correlationId"}
        assert body["status"] == 422
        assert body["path"] == "/score"

    def test_metrics_expose_the_score_distribution(self, client, ordinary_payment):
        """
        The distribution is the drift signal that matters: a degrading fraud model keeps
        returning numbers and nothing fails, but the shape of the histogram moves first.
        """
        client.post("/score", json=_camel(ordinary_payment), headers=_headers())

        text = client.get("/metrics").text

        assert "aegis_ml_risk_score_bucket" in text
        assert "aegis_ml_score_latency_seconds_bucket" in text
        assert 'aegis_ml_score_requests_total{outcome="success"}' in text
        assert "aegis_ml_model_info{" in text
        assert "aegis_ml_up 1" in text

    def test_the_platform_thresholds_are_score_bucket_edges(self, client):
        """
        45 and 80 are bucket edges on purpose, so "how many payments crossed the blocking
        threshold" is a direct read rather than an interpolation between two buckets.
        """
        text = client.get("/metrics").text

        assert 'aegis_ml_risk_score_bucket{le="45"}' in text
        assert 'aegis_ml_risk_score_bucket{le="80"}' in text

    def test_counts_an_unauthorised_attempt(self, client, ordinary_payment):
        client.post("/score", json=_camel(ordinary_payment))

        assert 'aegis_ml_score_requests_total{outcome="unauthorised"}' in client.get("/metrics").text


class TestOpenApi:
    def test_the_schema_is_generated(self, client):
        """
        Every service in the platform publishes OpenAPI; this one is no exception just because
        it is Python.
        """
        document = client.get("/openapi.json").json()

        assert "/score" in document["paths"]
        assert "/model" in document["paths"]
        assert "SYNTHETIC" in document["info"]["description"]


#: Fields whose wire name is not a mechanical camelCase of the Python name.
#:
#: These are transcribed rather than computed, because an earlier version of this helper used
#: the same transformation as the schema — so both agreed on ``paymentCount1H`` and the suite
#: passed while a real Java client sending ``paymentCount1h`` would have been rejected. See
#: ``test_wire_contract.py``, which pins the whole set literally.
_EXPLICIT_ALIASES = {
    "payment_count_1h": "paymentCount1h",
    "payment_count_24h": "paymentCount24h",
    "amount_sum_24h_minor": "amountSum24hMinor",
    "failed_payment_count_24h": "failedPaymentCount24h",
    "distinct_devices_30d": "distinctDevices30d",
    "distinct_countries_30d": "distinctCountries30d",
}


def _camel(payload: dict) -> dict:
    """
    Converts a snake_case fixture to the exact camelCase wire format.

    Kept in the test rather than relaxing the schema: the contract is camelCase, so a test that
    posted snake_case would be verifying a leniency production does not rely on.
    """
    def convert(name: str) -> str:
        if name in _EXPLICIT_ALIASES:
            return _EXPLICIT_ALIASES[name]
        head, *rest = name.split("_")
        return head + "".join(part.title() for part in rest)

    return {convert(key): value for key, value in payload.items()}
