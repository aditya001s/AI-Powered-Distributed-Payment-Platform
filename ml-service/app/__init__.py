"""
AegisPay fraud scoring service.

Measures fraud risk. Does not decide anything.

The package is deliberately split so that everything which determines whether a payment
gets blocked lives in modules that import no web framework at all:

* ``features``  — the feature contract, shared by training and serving
* ``dataset``   — the deterministic synthetic generator
* ``model``     — gradient boosting, calibration, persistence
* ``scoring``   — the engine: probability, 0-100 score, per-feature explanation
* ``main``      — a thin FastAPI adapter over ``scoring``

That split is not a testing convenience. A scoring engine that can only be exercised
through HTTP is a scoring engine whose numerical behaviour nobody checks.
"""

__all__ = ["__version__"]

# The API contract version, not the model version. A model retrain changes the model
# version and nothing else; this changes only when the request or response shape does.
__version__ = "1.0.0"
