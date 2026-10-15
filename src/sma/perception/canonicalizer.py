from __future__ import annotations

import re
import time
from collections.abc import Iterable
from typing import Any

from sma.perception.fingerprint import digest, normalize_dynamic, screen_fingerprint
from sma.perception.redaction import classify
from sma.protocol.models import CanonicalNode, CanonicalState, Rect

_ROLE_MAP = {
    "button": "button",
    "edittext": "textbox",
    "textfield": "textbox",
    "checkbox": "checkbox",
    "switch": "switch",
    "imageview": "image",
    "recyclerview": "list",
    "listview": "list",
    "scrollview": "scroll",
}


def _value(raw: dict[str, Any], *keys: str, default: Any = None) -> Any:
    for key in keys:
        if key in raw:
            return raw[key]
    return default


def _bounds(raw: Any) -> Rect | None:
    values: tuple[int, int, int, int] | None = None
    if isinstance(raw, (list, tuple)) and len(raw) == 4:
        values = tuple(map(int, raw))  # type: ignore[assignment]
    if isinstance(raw, dict):
        aliases = {"left": "left", "top": "top", "right": "right", "bottom": "bottom"}
        if all(key in raw for key in aliases):
            values = tuple(int(raw[key]) for key in aliases)  # type: ignore[assignment]
    if isinstance(raw, str):
        parsed = [int(x) for x in re.findall(r"-?\d+", raw)]
        if len(parsed) == 4:
            values = tuple(parsed)  # type: ignore[assignment]
    if values is not None:
        left, top, right, bottom = values
        if right < left or bottom < top:
            return None
        return Rect(left=left, top=top, right=right, bottom=bottom)
    return None


def _flatten(
    raw: Any, parent_path: str = "root"
) -> Iterable[tuple[dict[str, Any], str, str | None]]:
    if isinstance(raw, list):
        for index, child in enumerate(raw):
            yield from _flatten(child, f"{parent_path}/{index}")
        return
    if not isinstance(raw, dict):
        return
    if any(
        k in raw for k in ("class", "className", "text", "resource_id", "resourceId", "clickable")
    ):
        yield raw, parent_path, parent_path.rsplit("/", 1)[0] if "/" in parent_path else None
    children = _value(raw, "children", "nodes", "elements", default=[])
    if isinstance(children, (list, dict)):
        yield from _flatten(children, parent_path)


def canonicalize(
    raw: dict[str, Any], *, epoch: int = 1, revision: int = 1, captured_at: float | None = None
) -> CanonicalState:
    package = str(_value(raw, "package", "packageName", default="unknown"))
    window_id = str(_value(raw, "window_id", "windowId", default="0"))
    entries = list(_flatten(_value(raw, "elements", "nodes", "root", default=raw)))
    nodes: list[CanonicalNode] = []
    path_handles: dict[str, str] = {}
    collisions: dict[str, int] = {}
    for item, path, parent_path in entries:
        class_name = str(_value(item, "class", "className", default="android.view.View"))
        simple_class = class_name.rsplit(".", 1)[-1].casefold()
        role = next(
            (v for k, v in _ROLE_MAP.items() if k in simple_class),
            "text" if item.get("text") else "view",
        )
        text = _value(item, "text")
        description = _value(item, "content_description", "contentDescription", "description")
        resource_id = _value(item, "resource_id", "resourceId", "viewIdResourceName")
        accessibility_id = _value(item, "accessibility_id", "uniqueId", "sourceNodeId")
        label = str(text or description or resource_id or role)
        structural = digest(package, window_id, role, str(resource_id or ""), path)
        stable_id = str(resource_id or accessibility_id or "")
        semantic = digest(
            package, window_id, role, stable_id, "" if stable_id else normalize_dynamic(label)
        )
        base = f"a{int(semantic[:8], 16) % 100000:05d}"
        collision = collisions.get(base, 0)
        collisions[base] = collision + 1
        handle = base if collision == 0 else f"{base}-{collision + 1}"
        path_handles[path] = handle
        password = bool(_value(item, "password", "isPassword", default=False))
        sensitivity = classify(package, f"{label} {resource_id or ''}", password)
        actions = list(_value(item, "actions", "supportedActions", default=[]))
        clickable = bool(_value(item, "clickable", "isClickable", default=False))
        editable = bool(_value(item, "editable", "isEditable", default=False)) or role == "textbox"
        scrollable = bool(_value(item, "scrollable", "isScrollable", default=False))
        if clickable and "click" not in actions:
            actions.append("click")
        if editable and "set_text" not in actions:
            actions.append("set_text")
        if scrollable and "scroll" not in actions:
            actions.append("scroll")
        nodes.append(
            CanonicalNode(
                handle=handle,
                parent_handle=path_handles.get(parent_path or ""),
                package=package,
                window_id=window_id,
                resource_id=str(resource_id) if resource_id else None,
                accessibility_id=str(accessibility_id) if accessibility_id else None,
                role=role,
                class_name=class_name,
                text=str(text) if text not in (None, "") else None,
                content_description=str(description) if description not in (None, "") else None,
                clickable=clickable,
                editable=editable,
                scrollable=scrollable,
                enabled=bool(_value(item, "enabled", "isEnabled", default=True)),
                visible=bool(_value(item, "visible", "isVisibleToUser", default=True)),
                selected=bool(_value(item, "selected", "isSelected", default=False)),
                checked=_value(item, "checked", "isChecked"),
                bounds=_bounds(_value(item, "bounds", "boundsInScreen")),
                actions=tuple(sorted(set(map(str, actions)))),
                structural_signature=structural,
                semantic_signature=semantic,
                sensitivity=sensitivity,
            )
        )
    screen_hash = screen_fingerprint(package, nodes)
    screen_id = str(_value(raw, "screen_id", "activity", default=f"{package}/{screen_hash[:12]}"))
    return CanonicalState(
        package=package,
        screen_id=screen_id,
        window_id=window_id,
        epoch=epoch,
        revision=revision,
        rotation=int(_value(raw, "rotation", default=0)),
        nodes=nodes,
        captured_at=captured_at if captured_at is not None else time.time(),
    )
