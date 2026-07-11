#!/usr/bin/env python3
"""Regression guard for the dorny/paths-filter `pull-requests: read` permission.

Background (see docs/ISSUES.md, CI-PERM-1):
  dorny/paths-filter uses the GitHub REST API on `pull_request` events to fetch
  the list of changed files and requires the `pull-requests: read` permission.
  A workflow-level `permissions:` grant is INSUFFICIENT on its own: any job that
  declares its own `permissions:` block REPLACES (does not merge with) the
  workflow-level block, zeroing every unlisted scope to `none`. The grant must
  therefore be present at the JOB level for every job that runs
  dorny/paths-filter, otherwise the step fails with
  "Resource not accessible by integration" (HTTP 403) and the required CI check
  fails on every PR.

This script fails if any workflow under .github/workflows that uses
dorny/paths-filter has a job whose job-level `permissions:` block lacks
`pull-requests: read`.

Pure stdlib (no PyYAML dependency) so it runs on any Python 3 interpreter.
"""
from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS_DIR = REPO_ROOT / ".github" / "workflows"
PATHS_FILTER = "dorny/paths-filter"

# Permission scope keys recognised by GitHub Actions GITHUB_TOKEN.
PERM_SCOPES = {
    "actions", "attestations", "checks", "contents", "deployments",
    "discussions", "id-token", "issues", "models", "packages",
    "pages", "pull-requests", "repository-projects", "security-events",
    "statuses",
}
PERM_VALUES = {"read", "write", "none"}


def _parse_jobs(text: str):
    """Return {job_name: {"perms": set("scope: value"), "filter": bool}}.

    Minimal indentation-aware parser tailored to GitHub Actions workflow files
    using 2-space indentation (jobs at col 2, job keys at col 4, step list
    items at col 6, step keys at col 8). It only tracks what this guard needs.
    """
    jobs: dict[str, dict] = {}
    cur_job: str | None = None
    cur_perms: set[str] = set()
    cur_filter = False
    in_jobs = False
    in_job_perms = False

    def flush() -> None:
        nonlocal cur_job, cur_perms, cur_filter
        if cur_job is not None:
            jobs[cur_job] = {"perms": set(cur_perms), "filter": cur_filter}
        cur_job = None
        cur_perms = set()
        cur_filter = False

    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        content = raw.strip()

        if indent == 0:
            in_jobs = content == "jobs:"
            in_job_perms = False
            continue
        if not in_jobs:
            continue

        # New job declaration: 2-space indent, "name:" form.
        if indent == 2 and content.endswith(":"):
            flush()
            cur_job = content[:-1]
            in_job_perms = False
            continue
        if cur_job is None:
            continue

        # Job-level permissions block: 4-space "permissions:" key.
        if indent == 4 and content == "permissions:":
            in_job_perms = True
            continue
        if in_job_perms:
            if indent == 6 and ":" in content:
                key, _, val = content.partition(":")
                key = key.strip()
                val = val.strip()
                if key in PERM_SCOPES and val in PERM_VALUES:
                    cur_perms.add(f"{key}: {val}")
                continue
            in_job_perms = False

        # Detect paths-filter usage anywhere inside this job (step "uses:").
        if PATHS_FILTER in content:
            cur_filter = True

    flush()
    return jobs


def check_file(path: Path) -> list[str]:
    failures: list[str] = []
    jobs = _parse_jobs(path.read_text())
    if not jobs:
        failures.append(f"{path.name}: no jobs parsed (parser out of sync?)")
        return failures
    any_filter = False
    for jname, info in jobs.items():
        if not info["filter"]:
            continue
        any_filter = True
        if "pull-requests: read" not in info["perms"]:
            failures.append(
                f"{path.name}: job '{jname}' runs {PATHS_FILTER} but its "
                f"job-level permissions lack 'pull-requests: read' "
                f"(got {sorted(info['perms']) or 'none'}). A job-level "
                f"permissions block replaces the workflow-level one; add "
                f"'pull-requests: read' to the job."
            )
    if not any_filter:
        failures.append(
            f"{path.name}: no job using {PATHS_FILTER} found "
            "(remove this file from the guard or update the parser)."
        )
    return failures


def main() -> int:
    targets = ["android-ci.yml", "go-ci.yml"]
    all_failures: list[str] = []
    for name in targets:
        path = WORKFLOWS_DIR / name
        if not path.exists():
            all_failures.append(f"{name}: workflow file missing")
            continue
        all_failures.extend(check_file(path))

    if all_failures:
        print("FAIL: paths-filter permission regression detected:")
        for f in all_failures:
            print("  - " + f)
        return 1

    print("OK: every dorny/paths-filter job has job-level 'pull-requests: read'.")
    for name in targets:
        jobs = _parse_jobs((WORKFLOWS_DIR / name).read_text())
        for jname, info in jobs.items():
            if info["filter"]:
                print(f"  {name} :: {jname} -> {sorted(info['perms'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
