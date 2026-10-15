from __future__ import annotations

import hashlib
import re

from sma.protocol.models import CanonicalNode

_DYNAMIC = re.compile(r"\b\d{1,4}([:/.-]\d{1,4})*\b")


def normalize_dynamic(text: str) -> str:
    return " ".join(_DYNAMIC.sub("#", text.casefold()).split())


def digest(*parts: str, length: int = 16) -> str:
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()[:length]


def structural_fingerprint(nodes: list[CanonicalNode]) -> str:
    features = sorted(
        f"{n.role}:{bool(n.resource_id)}:{n.clickable}:{n.editable}:{n.scrollable}"
        for n in nodes
        if n.visible
    )
    return digest(*features, length=24)


def screen_fingerprint(package: str, nodes: list[CanonicalNode]) -> str:
    stable = sorted(
        f"{n.role}:{normalize_dynamic(n.resource_id or n.label)}"
        for n in nodes
        if n.visible and n.sensitivity.value == "public"
    )
    return digest(package, *stable, length=24)
