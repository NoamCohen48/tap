#!/usr/bin/env python3
"""Measure complete attachment lifecycles on an explicitly selected, otherwise idle device.

Start the daemon explicitly before running this script. Requires tap-e2e in this interpreter.
It does not start/stop a daemon, launch an app, reboot, or change device settings explicitly.
Normal attachment wakes the device, dismisses an insecure keyguard and may update driver APKs.
No samples are discarded: correlate deployment outliers with the daemon's lifecycle log.
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path
from typing import Any


def positive_cycles(value: str) -> int:
    count = int(value)
    if not 1 <= count <= 100:
        raise argparse.ArgumentTypeError("cycles must be between 1 and 100")
    return count


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial of an idle device")
    parser.add_argument("--cycles", type=positive_cycles, default=5)
    parser.add_argument("--output", type=Path, help="write metadata, all samples and summaries as JSON")
    args = parser.parse_args()

    from tap_e2e import TapClient

    report: dict[str, Any] = {"serial": args.serial, "requested_cycles": args.cycles, "samples": []}
    failed = False
    try:
        with TapClient.create() as client:
            info = client.info()
            report["daemon_version"] = info.daemon_version
            report["host_build_id"] = info.host_build_id
            report["protocol_version"] = info.protocol_version
            entry = next((entry for entry in client.devices() if entry.serial == args.serial), None)
            if entry is None or entry.state.name != "FREE":
                raise RuntimeError(f"device must be online, free and not quarantined: {entry}")
            if entry.properties is not None:
                report["device"] = {
                    "manufacturer": entry.properties.manufacturer,
                    "model": entry.properties.model,
                    "api_level": entry.properties.api_level,
                }
            with client.connect("attachment lifecycle benchmark") as connection:
                for index in range(args.cycles):
                    sample: dict[str, Any] = {"cycle": index + 1, "started_epoch_s": time.time()}
                    report["samples"].append(sample)
                    device = None
                    try:
                        started = time.perf_counter()
                        device = connection.attach_device(args.serial)
                        sample["attach_s"] = time.perf_counter() - started
                        started = time.perf_counter()
                        device.info()
                        sample["read_info_s"] = time.perf_counter() - started
                    finally:
                        if device is not None:
                            started = time.perf_counter()
                            detail = device.detach()
                            sample["detach_s"] = time.perf_counter() - started
                            if detail:
                                raise RuntimeError(f"detach could not prove cleanup: {detail}")
                    sample["lifecycle_s"] = sample["attach_s"] + sample["detach_s"]
                    sample["ok"] = True
                    print(json.dumps(sample), flush=True)
    except Exception as error:
        failed = True
        report["error"] = f"{type(error).__name__}: {error}"
        print(report["error"], file=sys.stderr)
    finally:
        report["completed_cycles"] = sum(sample.get("ok", False) for sample in report["samples"])
        report["summary"] = {}
        for field in ("attach_s", "detach_s", "lifecycle_s", "read_info_s"):
            values = [sample[field] for sample in report["samples"] if sample.get("ok", False)]
            if values:
                report["summary"][field] = {
                    "median": statistics.median(values), "min": min(values), "max": max(values),
                }
        print(json.dumps({"completed_cycles": report["completed_cycles"], "summary": report["summary"]}), flush=True)
        if args.output:
            args.output.write_text(json.dumps(report, indent=2) + "\n")
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
