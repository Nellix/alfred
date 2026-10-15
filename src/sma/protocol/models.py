from __future__ import annotations

from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator


class Sensitivity(StrEnum):
    PUBLIC = "public"
    PERSONAL = "personal"
    SECRET = "secret"
    EXCLUDED = "excluded"


class Rect(BaseModel):
    left: int
    top: int
    right: int
    bottom: int

    @field_validator("right")
    @classmethod
    def valid_right(cls, value: int, info: Any) -> int:
        if "left" in info.data and value < info.data["left"]:
            raise ValueError("right must not precede left")
        return value

    @field_validator("bottom")
    @classmethod
    def valid_bottom(cls, value: int, info: Any) -> int:
        if "top" in info.data and value < info.data["top"]:
            raise ValueError("bottom must not precede top")
        return value

    @property
    def center(self) -> tuple[int, int]:
        return ((self.left + self.right) // 2, (self.top + self.bottom) // 2)


class CanonicalNode(BaseModel):
    handle: str
    parent_handle: str | None = None
    package: str
    window_id: str
    resource_id: str | None = None
    accessibility_id: str | None = None
    role: str
    class_name: str
    text: str | None = None
    content_description: str | None = None
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False
    enabled: bool = True
    visible: bool = True
    selected: bool = False
    checked: bool | None = None
    bounds: Rect | None = None
    actions: tuple[str, ...] = ()
    structural_signature: str
    semantic_signature: str
    sensitivity: Sensitivity = Sensitivity.PUBLIC
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)

    @property
    def label(self) -> str:
        return (self.text or self.content_description or self.resource_id or self.role).strip()

    @property
    def actionable(self) -> bool:
        return (
            self.enabled
            and self.visible
            and bool(self.clickable or self.editable or self.scrollable or self.actions)
        )


class CanonicalState(BaseModel):
    package: str
    screen_id: str
    window_id: str
    epoch: int = Field(ge=1)
    revision: int = Field(ge=1)
    rotation: int = 0
    nodes: list[CanonicalNode]
    captured_at: float
    source: str = "accessibility"


class NodeChange(BaseModel):
    handle: str
    changes: dict[str, Any]


class UIDelta(BaseModel):
    type: Literal["ui_delta"] = "ui_delta"
    epoch: int
    base_revision: int
    revision: int
    package: str
    screen_id: str
    window_id: str
    rotation: int = 0
    captured_at: float
    added: list[CanonicalNode] = Field(default_factory=list)
    removed: list[str] = Field(default_factory=list)
    updated: list[NodeChange] = Field(default_factory=list)
    major_transition: bool = False


class SemanticAction(BaseModel):
    action: Literal[
        "launch_app", "open_deep_link", "click", "set_text", "scroll", "back", "wait", "stop"
    ]
    target: str | None = None
    value: str | None = None
    package: str | None = None
    uri: str | None = None
    expected_epoch: int | None = None
    expected_revision: int | None = None
    expected_screen: str | None = None
    timeout_seconds: float = Field(default=5.0, gt=0, le=60)


class ActionStatus(StrEnum):
    SUCCESS = "success"
    STALE_STATE = "STALE_STATE"
    TARGET_NOT_FOUND = "TARGET_NOT_FOUND"
    AMBIGUOUS_TARGET = "AMBIGUOUS_TARGET"
    ACTION_NOT_SUPPORTED = "ACTION_NOT_SUPPORTED"
    PERMISSION_DENIED = "PERMISSION_DENIED"
    VERIFICATION_FAILED = "VERIFICATION_FAILED"
    TIMEOUT = "TIMEOUT"
    USER_INTERRUPTED = "USER_INTERRUPTED"
    DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED"


class ActionResult(BaseModel):
    status: ActionStatus
    action: str
    previous_revision: int | None = None
    current_revision: int | None = None
    verified: bool = False
    message: str | None = None
