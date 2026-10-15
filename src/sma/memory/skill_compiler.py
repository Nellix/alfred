from __future__ import annotations

import re
from typing import Any
from urllib.parse import quote, quote_plus

from pydantic import BaseModel, Field

from sma.memory.skill_engine import ParameterSpec, Skill, SkillStep
from sma.protocol.intents import SemanticIntent
from sma.protocol.models import SemanticAction

_UNSTABLE_TARGET = re.compile(r"^(?:h:|node:|accessibility:)", re.I)
_COMMIT_TARGET = re.compile(
    r"^label:\s*(?:send|buy|purchase|pay|delete|transfer|confirm order)\b", re.I
)
_TEMPLATE_PARAMETER = re.compile(r"\$\{([a-zA-Z_][a-zA-Z0-9_]*)(?:\|(?:url|query))?\}")
_STRUCTURAL_PARAMETERS = {"channel", "provider"}


class TraceStep(BaseModel):
    action: SemanticAction
    source_screen: str
    destination_screen: str
    verified: bool


class SuccessfulTrace(BaseModel):
    goal: str
    steps: list[TraceStep] = Field(min_length=1, max_length=50)


def compile_proposal(trace: SuccessfulTrace, skill_id: str) -> Skill:
    """Compile a successful trace into an explicitly unapproved skill proposal.

    Dynamic parameter inference is intentionally out of scope for this minimal compiler. A proposal
    must be reviewed, parameterized if appropriate, and pass replay tests before approval.
    """
    if not all(step.verified for step in trace.steps):
        raise ValueError("every trace step must have a verified transition")
    steps = [
        SkillStep(
            action=step.action.action,
            target=step.action.target,
            value=step.action.value,
            package=step.action.package,
            uri=step.action.uri,
            screen=step.action.expected_screen,
            timeout_seconds=step.action.timeout_seconds,
        )
        for step in trace.steps
        if step.action.action != "stop"
    ]
    if not steps:
        raise ValueError("trace contains no executable steps")
    return Skill(
        id=skill_id,
        version=1,
        precondition_screen=trace.steps[0].source_screen,
        steps=steps,
        approved=False,
    )


def compile_parameterized_proposal(
    intent: SemanticIntent,
    trace: SuccessfulTrace,
    skill_id: str,
    *,
    allow_unreferenced_parameters: bool = False,
) -> Skill:
    """Generalize a verified trace without persisting the prompt or observed slot values."""
    if not all(step.verified for step in trace.steps):
        raise ValueError("every trace step must have a verified transition")
    parameters = {name: _parameter_spec(name, value) for name, value in intent.parameters.items()}
    steps: list[SkillStep] = []
    for trace_step in trace.steps:
        action = trace_step.action
        if action.action == "stop":
            continue
        if action.target and _UNSTABLE_TARGET.match(action.target):
            raise ValueError("learned skills cannot contain ephemeral node handles")
        if action.target and _COMMIT_TARGET.match(action.target):
            raise ValueError("learned skills cannot contain commit actions")
        steps.append(
            SkillStep(
                action=action.action,
                target=_parameterize(action.target, intent.parameters),
                value=_parameterize(action.value, intent.parameters),
                package=_parameterize(action.package, intent.parameters),
                uri=_parameterize(action.uri, intent.parameters),
                screen=action.expected_screen,
                timeout_seconds=action.timeout_seconds,
            )
        )
    if not steps:
        raise ValueError("trace contains no executable steps")
    referenced = {
        match.group(1)
        for step in steps
        for value in (step.target, step.value, step.package, step.uri)
        if value is not None
        for match in _TEMPLATE_PARAMETER.finditer(value)
    }
    missing_content = intent.parameters.keys() - referenced - _STRUCTURAL_PARAMETERS
    if missing_content and not allow_unreferenced_parameters:
        raise ValueError(
            f"trace did not parameterize user-content slots: {sorted(missing_content)}"
        )
    return Skill(
        id=skill_id,
        version=1,
        parameters=parameters,
        precondition_screen=trace.steps[0].source_screen,
        steps=steps,
        approved=False,
    )


def _parameter_spec(name: str, value: Any) -> ParameterSpec:
    if type(value) is bool:
        return ParameterSpec(type="boolean")
    if type(value) is int:
        return ParameterSpec(type="integer")
    if not isinstance(value, str) or not value:
        raise ValueError(f"intent parameter {name!r} has an unsupported value")
    if name in {"channel", "provider"}:
        return ParameterSpec(type="string", pattern=f"^(?i:{re.escape(value)})$")
    return ParameterSpec(type="string", pattern=r"^[^\r\n]{1,1000}$")


def _parameterize(value: str | None, parameters: dict[str, Any]) -> str | None:
    if value is None:
        return None
    parameterized = value
    replacements: dict[str, str] = {}
    for name, parameter_value in parameters.items():
        rendered = str(parameter_value)
        if not rendered:
            continue
        replacements[rendered] = f"${{{name}}}"
        url_encoded = quote(rendered, safe="")
        query_encoded = quote_plus(rendered)
        if url_encoded != rendered:
            replacements[url_encoded] = f"${{{name}|url}}"
        if query_encoded != rendered:
            replacements[query_encoded] = f"${{{name}|query}}"
    for observed, template in sorted(
        replacements.items(), key=lambda item: (-len(item[0]), item[0])
    ):
        parameterized = parameterized.replace(observed, template)
    return parameterized
