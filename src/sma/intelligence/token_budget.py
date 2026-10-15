from __future__ import annotations

from dataclasses import dataclass


def estimate_tokens(text: str, model: str | None = None) -> int:
    """Estimate tokens, using tiktoken when installed and a labeled byte heuristic otherwise."""
    try:
        import tiktoken

        try:
            encoding = tiktoken.encoding_for_model(model or "gpt-4o-mini")
        except KeyError:
            encoding = tiktoken.get_encoding("o200k_base")
        return len(encoding.encode(text))
    except Exception:
        # tiktoken lazily downloads some vocabularies. Offline execution must remain
        # deterministic, so any missing package/cache/network condition uses the heuristic.
        return max(1, (len(text.encode("utf-8")) + 3) // 4)


@dataclass
class TokenBudget:
    maximum: int = 8_000
    used: int = 0

    @property
    def remaining(self) -> int:
        return max(0, self.maximum - self.used)

    def charge(self, tokens: int) -> None:
        if tokens > self.remaining:
            raise RuntimeError(
                f"token budget exceeded: requested {tokens}, remaining {self.remaining}"
            )
        self.used += tokens
