from __future__ import annotations

import contextlib
import json
import signal
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Optional, Sequence, TextIO

from .config import ConfigError, load_perf2_profile, parse_args
from .orchestrator import Orchestrator
from .remote import RemoteMonitorManager
from .task_api import TaskAPI


@dataclass(frozen=True)
class CLIDependencies:
    repo_root: Path
    load_profile: Callable
    task_api_factory: Callable
    remote_factory: Callable
    orchestrator_factory: Callable


_SAFE_SUMMARY_FIELDS = (
    "runId",
    "mode",
    "snapshotTaskCount",
    "selectedAccountCount",
    "targetGroupCount",
    "targetPairCount",
    "baselineFinalLag",
    "observedPeakProducedPerSecond",
    "observedPeakConsumedPerSecond",
    "maxLag",
    "maxLagAt",
    "capacityConclusion",
    "allSnapshotTasksResumed",
    "incomplete",
    "failureClass",
)


def run(
    argv: Sequence[str],
    stdout: TextIO,
    stderr: TextIO,
    dependencies: Optional[CLIDependencies] = None,
) -> int:
    with contextlib.redirect_stdout(stdout):
        try:
            options = parse_args(argv)
        except SystemExit as error:
            return int(error.code or 0)
        except ConfigError:
            stderr.write("invalid_arguments\n")
            return 2
    dependencies = dependencies or real_dependencies()
    try:
        profile = dependencies.load_profile(dependencies.repo_root, options.env)
        task_api = dependencies.task_api_factory(profile, options)
        remote_manager = dependencies.remote_factory(profile, options)
        orchestrator = dependencies.orchestrator_factory(
            options=options,
            task_api=task_api,
            remote_manager=remote_manager,
            results_root=dependencies.repo_root / "armada-deploy" / "perf-results",
            zhuan_repo=dependencies.repo_root.parent / "whatsapp-server-feature-android-zhuan",
            profile=profile,
        )
        with _controlled_termination():
            exit_code = orchestrator.run()
    except Exception:
        stderr.write("setup_failed\n")
        return 1
    safe_summary = {
        key: orchestrator.last_summary.get(key)
        for key in _SAFE_SUMMARY_FIELDS
        if key in orchestrator.last_summary
    }
    safe_summary["resultsDirectory"] = str(orchestrator.run_dir)
    json.dump(safe_summary, stdout, ensure_ascii=False, sort_keys=True, indent=2)
    stdout.write("\n")
    return exit_code


def real_dependencies() -> CLIDependencies:
    repo_root = Path(__file__).resolve().parents[3]
    return CLIDependencies(
        repo_root=repo_root,
        load_profile=load_perf2_profile,
        task_api_factory=lambda profile, options: TaskAPI(profile.public_url, options.tenant),
        remote_factory=lambda profile, options: RemoteMonitorManager(
            profile, min_free_gib=options.min_free_gib
        ),
        orchestrator_factory=Orchestrator,
    )


@contextlib.contextmanager
def _controlled_termination():
    signals = (signal.SIGINT, signal.SIGTERM)
    previous = {signum: signal.getsignal(signum) for signum in signals}

    def interrupt(_signum, _frame):
        raise KeyboardInterrupt

    try:
        for signum in signals:
            signal.signal(signum, interrupt)
        yield
    finally:
        for signum in signals:
            signal.signal(signum, previous[signum])


def main() -> int:
    return run(sys.argv[1:], sys.stdout, sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
