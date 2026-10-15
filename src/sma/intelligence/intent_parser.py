from __future__ import annotations

import hashlib
import re
import unicodedata

from sma.protocol.intents import IntentValue, SemanticIntent


class IntentParser:
    """Small deterministic parser used before any model call.

    This intentionally covers only common, high-value patterns. A small on-device classifier can
    replace or augment it later without changing the cache schema.
    """

    _SETTINGS_SEARCH = (
        re.compile(r"^(?:search|find) (?:android )?settings for (?P<query>.+)$", re.I),
        re.compile(r"^find (?P<query>.+) in (?:android )?settings$", re.I),
    )
    _SETTINGS_OPEN = re.compile(r"^(?:open|launch|start)(?: the)? (?:android )?settings$", re.I)
    _YOUTUBE_PLAY = (
        re.compile(r"^(?:open )?youtube(?: and)? play (?P<query>.+)$", re.I),
        re.compile(r"^play (?P<query>.+) (?:on|in) youtube$", re.I),
    )
    _EMAIL_CHECK = re.compile(r"^(?:check|open)(?: my)? (?:email|emails|mail|inbox)$", re.I)
    _NAVIGATE = re.compile(
        r"^(?:navigate|go|take me|give me directions) to (?P<destination>.+?)"
        r"(?: (?:using|with|in) (?:google )?maps)?$",
        re.I,
    )
    _MESSAGE = re.compile(
        r"^(?:send|write) (?:a )?message (?:to (?P<recipient>[^:]+?) )?"
        r"(?:via|on) (?P<channel>telegram|whatsapp)(?:: (?P<message>.+))?$",
        re.I,
    )

    def parse(self, goal: str) -> SemanticIntent | None:
        normalized = self._normalize(goal)
        if not normalized:
            return None
        fingerprint = hashlib.sha256(normalized.encode()).hexdigest()

        match = self._first_match(self._SETTINGS_SEARCH, normalized)
        if match:
            return self._intent(
                "android.settings.search",
                {"query": match.group("query").strip()},
                0.99,
                fingerprint,
            )
        if self._SETTINGS_OPEN.fullmatch(normalized):
            return self._intent("android.settings.open", {}, 1.0, fingerprint)

        match = self._first_match(self._YOUTUBE_PLAY, normalized)
        if match:
            return self._intent(
                "media.youtube.play",
                {"query": match.group("query").strip()},
                0.97,
                fingerprint,
            )
        if self._EMAIL_CHECK.fullmatch(normalized):
            return self._intent("email.inbox.check", {}, 0.98, fingerprint)

        match = self._NAVIGATE.fullmatch(normalized)
        if match:
            return self._intent(
                "navigation.route",
                {"destination": match.group("destination").strip()},
                0.95,
                fingerprint,
            )

        match = self._MESSAGE.fullmatch(normalized)
        if match:
            parameters = {
                name: value.strip()
                for name in ("recipient", "channel", "message")
                if (value := match.group(name))
            }
            return self._intent("message.compose", parameters, 0.95, fingerprint)
        return None

    @staticmethod
    def _normalize(goal: str) -> str:
        normalized = unicodedata.normalize("NFKC", goal).strip()
        return re.sub(r"\s+", " ", normalized)

    @staticmethod
    def _first_match(patterns: tuple[re.Pattern[str], ...], text: str) -> re.Match[str] | None:
        for pattern in patterns:
            if match := pattern.fullmatch(text):
                return match
        return None

    @staticmethod
    def _intent(
        name: str,
        parameters: dict[str, IntentValue],
        confidence: float,
        fingerprint: str,
    ) -> SemanticIntent:
        return SemanticIntent(
            canonical_intent=name,
            parameters=parameters,
            confidence=confidence,
            request_fingerprint=fingerprint,
        )
