"""Structured death evidence shared by SDK and agent history presentation."""
from __future__ import annotations

import copy
import datetime


def _at(seconds: float) -> str:
    return datetime.datetime.fromtimestamp(seconds, datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def death_document(entry, connection_id: str, clock_id: str) -> dict:
    """Keep original identities, device time, redacted diagnostics and host receipt time."""
    envelope = entry.death
    value = envelope.get("death", {})
    kind = {"KIND_CRASH": "crash", "KIND_ANR": "anr"}.get(value.get("kind"), "unknown")
    result = {"connection_id": connection_id, "attached_device_id": envelope.get("attached_device_id", ""),
        "serial": envelope.get("serial", ""), "sequence": int(value.get("sequence", 0)), "kind": kind,
        "at": _at(entry.at), "timestamp_source": "host_receipt", "clock_id": clock_id,
        "cursor": f"{connection_id}:{entry.cursor}", "host_monotonic_ns": str(entry.host_monotonic_ns),
        "package_name": value.get("package_name", ""), "process_name": value.get("process_name", ""),
        "pid": value.get("pid", 0), "exception_class": value.get("exception_class", ""),
        "message": value.get("message", ""), "stack_trace": value.get("stack_trace", ""),
        "watched": value.get("watched", False), "handled_at_capture": value.get("handled", False)}
    if "at_epoch_ms" in value:
        result["device_time_ms"] = str(value["at_epoch_ms"])
        try:
            result["device_at"] = _at(int(value["at_epoch_ms"]) / 1000)
        except (ValueError, OverflowError, OSError):
            result["device_time_unavailable"] = "outside the supported calendar range; original milliseconds preserved"
    result["stack_trace_available"] = bool(result["stack_trace"])
    return result


def combined_deaths(data: dict) -> list[dict]:
    """Merge durable independent observations with older step-reported metadata, without inventing identity."""
    deaths = []
    seen = set()
    for value in data.get("deaths", []):
        key = (value.get("connection_id"), value.get("attached_device_id"), str(value.get("sequence")))
        if key not in seen:
            deaths.append(copy.deepcopy(value))
            seen.add(key)
    for step in data.get("steps", []):
        owner = step.get("connection_id") or data.get("connection_id")
        for reported in step.get("app_deaths", []):
            matches = [death for death in deaths if death.get("serial") == reported.get("serial")
                and str(death.get("sequence")) == str(reported.get("sequence"))
                and death.get("connection_id") == owner]
            if matches:
                if len(matches) == 1:
                    matches[0]["reported_by_step"] = step.get("id")
                    matches[0]["expected"] = reported.get("expected", False)
                continue
            deaths.append({**copy.deepcopy(reported), "connection_id": owner,
                "at": step.get("finished_at") or step.get("started_at", ""), "timestamp_source": "step_report",
                "reported_by_step": step.get("id"), "details_unavailable": "only step-reported metadata was retained"})
    return sorted(deaths, key=lambda death: (death.get("at", ""), death.get("serial", ""), str(death.get("sequence", ""))))


def death_rows(data: dict, *, details: bool = False) -> list[tuple[str, str]]:
    rows = []
    for death in combined_deaths(data):
        at = death.get("at", "")
        kind = {"crash": "CRASH", "anr": "ANR"}.get(death.get("kind", ""), "APP DEATH")
        context = ": ".join(str(death[key]) for key in ("exception_class", "message") if death.get(key))
        summary = f"{at}  {death.get('serial', '?')}  {kind} {death.get('package_name', '?')}"
        if "pid" in death:
            summary += f" (pid {death['pid']})"
        if context:
            summary += f": {context}"
        rows.append((at, summary))
        if details:
            trace = death.get("stack_trace", "")
            rows.extend((at, f"    | {line}") for line in trace.splitlines())
            if not trace:
                rows.append((at, f"    | {death.get('details_unavailable', 'No stack trace was supplied by the captured death source')}"))
    return rows
