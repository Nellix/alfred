from __future__ import annotations

import re

from sma.protocol.models import Sensitivity

_OTP = re.compile(r"\b(?:otp|one[- ]time|verification code)\D{0,12}(\d{4,8})\b", re.I)
_CARD = re.compile(r"\b(?:\d[ -]*?){13,19}\b")
_EMAIL = re.compile(r"\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b")
_SECRET_HINTS = re.compile(r"password|passcode|pin|cvv|recovery code|private key", re.I)
_EXCLUDED_PACKAGES = ("bank", "authenticator", "password", "wallet")


def classify(package: str, text: str, password: bool = False) -> Sensitivity:
    if any(part in package.lower() for part in _EXCLUDED_PACKAGES):
        return Sensitivity.EXCLUDED
    if password or _SECRET_HINTS.search(text) or _OTP.search(text) or _CARD.search(text):
        return Sensitivity.SECRET
    if _EMAIL.search(text):
        return Sensitivity.PERSONAL
    return Sensitivity.PUBLIC


def redact(text: str, sensitivity: Sensitivity) -> str:
    if sensitivity in {Sensitivity.SECRET, Sensitivity.EXCLUDED}:
        return "[REDACTED]"
    if sensitivity is Sensitivity.PERSONAL:
        return _EMAIL.sub("[EMAIL]", text)
    return _CARD.sub("[PAYMENT]", _OTP.sub(lambda m: m.group(0).replace(m.group(1), "[OTP]"), text))
