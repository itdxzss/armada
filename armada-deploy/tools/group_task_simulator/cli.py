from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Sequence, TextIO

from .load import LoadConfig, run_load
from .scenario import ScenarioError, replay_scenario
from .simulator import SimulatorSafetyError, WorldStateError


def run(argv: Sequence[str], stdout: TextIO, stderr: TextIO) -> int:
    parser = argparse.ArgumentParser(description="Zero-egress group-task protocol simulator")
    subparsers = parser.add_subparsers(dest="command", required=True)
    replay = subparsers.add_parser("replay", help="replay one versioned scenario")
    replay.add_argument("--scenario", required=True, type=Path)
    load = subparsers.add_parser("load", help="run zero-egress simulator load")
    load.add_argument("--executions", required=True, type=int)
    load.add_argument("--concurrency", required=True, type=int)
    load.add_argument("--command-rate", required=True, type=float)
    load.add_argument("--backend", choices=("ANDROID", "WEB", "MIXED"), required=True)
    load.add_argument("--replay-every", type=int, default=0)
    try:
        options = parser.parse_args(argv)
    except SystemExit as error:
        return int(error.code or 0)
    try:
        if options.command == "replay":
            report = replay_scenario(options.scenario)
            payload = {
                "scenarioId": report.scenario_id,
                "passed": report.passed,
                "mismatches": report.mismatches,
                "actual": report.actual,
            }
        else:
            load_report = run_load(LoadConfig(
                executions=options.executions,
                concurrency=options.concurrency,
                command_rate=options.command_rate,
                backend=options.backend,
                replay_every=options.replay_every,
            ))
            payload = load_report.as_dict()
    except (ScenarioError, SimulatorSafetyError, WorldStateError, ValueError):
        stderr.write("scenario_failed\n")
        return 2
    json.dump(payload, stdout, ensure_ascii=False, sort_keys=True, indent=2)
    stdout.write("\n")
    return 0 if payload["passed"] else 1


def main() -> int:
    return run(sys.argv[1:], sys.stdout, sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
