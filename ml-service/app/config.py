"""
Configuration, read once from the environment.

Every value that differs between a laptop and production comes from an environment
variable, and the names match the ones already documented in ``.env.example`` so there is
exactly one place to look them up.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer, got {raw!r}") from exc


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number, got {raw!r}") from exc


@dataclass(frozen=True)
class Settings:
    """Resolved configuration for one process."""

    # ---- deployment ----

    platform_env: str = field(default_factory=lambda: os.getenv("PLATFORM_ENV", "local"))
    port: int = field(default_factory=lambda: _env_int("SERVER_PORT", 8000))

    # ---- model artifact ----

    model_dir: Path = field(
        default_factory=lambda: Path(os.getenv("MODEL_DIR", "models")).expanduser()
    )
    model_name: str = field(default_factory=lambda: os.getenv("MODEL_NAME", "fraud_hgb"))

    # ---- training ----

    training_rows: int = field(default_factory=lambda: _env_int("ML_TRAINING_ROWS", 120_000))
    fraud_rate: float = field(default_factory=lambda: _env_float("ML_FRAUD_RATE", 0.017))

    #: Fixed so a rebuild produces an identical model. A model that changed on every
    #: build would make a disputed score impossible to reproduce six months later.
    random_seed: int = field(default_factory=lambda: _env_int("ML_RANDOM_SEED", 42))

    # ---- authentication ----

    #: Shared secret presented by fraud-service as ``X-API-Key``.
    api_key: str = field(default_factory=lambda: os.getenv("ML_API_KEY", ""))

    @property
    def artifact_path(self) -> Path:
        """Where the trained model lives."""
        return self.model_dir / f"{self.model_name}.joblib"

    @property
    def metadata_path(self) -> Path:
        """
        Training metrics and provenance, written next to the artifact.

        Kept as a separate JSON file rather than only inside the pickle so that the
        numbers can be read — and diffed in review — without loading scikit-learn.
        """
        return self.model_dir / f"{self.model_name}.metadata.json"

    @property
    def is_local(self) -> bool:
        return self.platform_env.lower() in {"local", "test"}

    def require_api_key(self) -> str:
        """
        The API key, refusing to start without one outside local development.

        Failing at startup rather than per-request is deliberate: an unauthenticated
        scoring endpoint is a way to probe the fraud model's decision boundary, which is
        exactly the reconnaissance an attacker wants before choosing an amount.
        """
        if self.api_key:
            return self.api_key
        if self.is_local:
            return ""
        raise RuntimeError(
            "ML_API_KEY must be set outside local development: an unauthenticated "
            "scoring endpoint lets anyone map the model's decision boundary"
        )


_settings: Settings | None = None


def settings() -> Settings:
    """The process-wide settings, resolved on first use."""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings() -> None:
    """Drops the cached settings. Used by tests that manipulate the environment."""
    global _settings
    _settings = None
