from __future__ import annotations

import re

from sma.protocol.models import CanonicalNode, SemanticAction, Sensitivity


class PolicyViolation(PermissionError):
    pass


_HIGH_RISK = re.compile(
    r"\b(send|purchase|buy|pay|delete|factory reset|change password|disable security|transfer)\b",
    re.I,
)
_DANGEROUS_SCHEMES = {"file", "javascript", "intent"}


class ActionPolicy:
    def validate(self, action: SemanticAction, node: CanonicalNode | None = None) -> None:
        material = " ".join(
            filter(None, [action.value, action.uri, action.package, node.label if node else None])
        )
        if _HIGH_RISK.search(material):
            raise PolicyViolation("high-risk or destructive operations are disabled in the PoC")
        if node and node.sensitivity in {Sensitivity.SECRET, Sensitivity.EXCLUDED}:
            raise PolicyViolation("actions on sensitive or excluded content are blocked")
        if action.action == "open_deep_link" and action.uri:
            scheme = action.uri.partition(":")[0].casefold()
            if scheme in _DANGEROUS_SCHEMES:
                raise PolicyViolation(f"deep-link scheme {scheme!r} is blocked")
