"""
The wire contract, pinned literally.
"""

from __future__ import annotations

from app.schemas import ScoreRequest, ScoreResponse

#: Exactly what fraud-service sends. Written out by hand, deliberately.
#:
#: An earlier version of this test computed the expected names with the same camelCase
#: transformation the schema uses, so the two agreed on a wrong answer and the suite passed
#: while a real Java client would have been rejected. The bug: pydantic's ``to_camel`` turns
#: ``payment_count_1h`` into ``paymentCount1H``, and Jackson sends ``paymentCount1h``.
#:
#: So this list is transcribed, not derived. If it disagrees with the schema, one of them is
#: wrong and a human has to decide which — which is the point.
REQUEST_FIELDS: frozenset[str] = frozenset({
    "paymentId",
    "transactionId",
    "amountMinor",
    "currency",
    "paymentType",
    "hourOfDay",
    "dayOfWeek",
    "accountAgeDays",
    "priorPaymentCount",
    "priorChargebackCount",
    "avgAmountMinor",
    "maxAmountMinor",
    "paymentCount1h",
    "paymentCount24h",
    "amountSum24hMinor",
    "failedPaymentCount24h",
    "secondsSinceLastPayment",
    "isNewDevice",
    "distinctDevices30d",
    "isNewCountry",
    "distinctCountries30d",
    "countryCode",
    "distanceFromLastKm",
    "hasGeo",
    "isNewPayee",
    "payeeMerchantCategory",
})

RESPONSE_FIELDS: frozenset[str] = frozenset({
    "fraudProbability",
    "riskScore",
    "modelVersion",
    "features",
    "contributions",
    "scoringLatencyMs",
})


def _wire_names(model) -> set[str]:
    return {
        field.alias or name
        for name, field in model.model_fields.items()
    }


class TestRequestContract:
    def test_the_wire_field_names_are_exactly_these(self):
        assert _wire_names(ScoreRequest) == set(REQUEST_FIELDS)

    def test_digit_suffixed_fields_use_a_lowercase_unit(self):
        """
        The specific bug this file exists for.

        Jackson serialising a Java field ``paymentCount1h`` sends ``paymentCount1h``. pydantic's
        ``to_camel`` would produce ``paymentCount1H``. One character, six fields, and the
        symptom is six features silently absent from every score.
        """
        for name in ("paymentCount1h", "paymentCount24h", "amountSum24hMinor",
                     "failedPaymentCount24h", "distinctDevices30d", "distinctCountries30d"):
            assert name in REQUEST_FIELDS
            assert name in _wire_names(ScoreRequest)

    def test_a_java_style_payload_is_accepted(self):
        """
        Every field at once, in the exact form fraud-service will send. This is the test that
        would have caught the alias bug.
        """
        payload = {name: None for name in REQUEST_FIELDS}
        payload["amountMinor"] = 150_000
        payload["hasGeo"] = False

        request = ScoreRequest.model_validate(payload)

        assert request.amount_minor == 150_000
        assert request.payment_count_1h is None

    def test_the_only_required_field_is_the_amount(self):
        """
        Everything else is genuinely optional: a new account has no history, an API-channel
        payment has no device, a customer who denied location has no coordinates.
        """
        request = ScoreRequest.model_validate({"amountMinor": 1})
        assert request.amount_minor == 1

    def test_features_reach_the_derivation_as_snake_case(self):
        """
        ``derive`` reads snake_case keys, so a by-alias dump here would silently produce an
        all-absent feature vector.
        """
        request = ScoreRequest.model_validate({"amountMinor": 1, "paymentCount1h": 4})
        features = request.to_features()

        assert features["amount_minor"] == 1
        assert features["payment_count_1h"] == 4


class TestResponseContract:
    def test_the_wire_field_names_are_exactly_these(self):
        assert _wire_names(ScoreResponse) == set(RESPONSE_FIELDS)

    def test_serialises_by_alias(self):
        response = ScoreResponse(
            fraud_probability=0.5,
            risk_score=50,
            model_version="fraud_hgb-test",
            features={"amount_minor": 1.0},
            contributions=[],
            scoring_latency_ms=3,
        )
        body = response.model_dump(by_alias=True)

        assert set(body) == set(RESPONSE_FIELDS)
        # Feature keys stay snake_case: they are data, not field names.
        assert "amount_minor" in body["features"]
