from __future__ import annotations

from pydantic import BaseModel, Field

IntentValue = str | int | bool


class SemanticIntent(BaseModel):
    """A privacy-preserving, local interpretation of a user request.

    The original prompt and normalized text are deliberately not retained. The fingerprint is
    useful for local diagnostics but is excluded from serialization by default.
    """

    canonical_intent: str = Field(pattern=r"^[a-z][a-z0-9_.]*$")
    parameters: dict[str, IntentValue] = Field(default_factory=dict)
    confidence: float = Field(ge=0.0, le=1.0)
    request_fingerprint: str = Field(exclude=True)
