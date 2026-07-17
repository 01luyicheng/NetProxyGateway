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


# ---------------------------------------------------------------------------
# CI-DEP-1 regression guard
# ---------------------------------------------------------------------------
# Asserts that `.github/workflows/pr-checks.yml` retains a `dependency-review`
# job using `actions/dependency-review-action` with `fail-on-severity` of at
# most `high` (i.e. `low`, `moderate`, or `high`). This job is the ONLY
# universally-triggered, non-masked, CVSS>=7.0 dependency-CVE gate covering
# both the Go and Android ecosystems on every PR.
#
# Other gates are insufficient on their own:
#   - go-ci.yml `govulncheck` step is masked (`continue-on-error: true`) per
#     CI-MASK-3 (see docs/ISSUES.md).
#   - android-ci.yml `dependencyCheckAnalyze` only fails at CVSS>=9.0
#     (`failBuildOnCVSS = 9.0f` in android/app/build.gradle.kts) and was
#     previously masked per CI-MASK-2.
#   - security.yml is path-filtered and explicitly "NOT a required check".
#
# Removing this job (as PR #71 did in N90, and as PRs #79 / #82 did again)
# eliminates the only failing-check signal for CVSS 7.0-8.9 dependency CVEs
# in either ecosystem. See docs/ISSUES.md CI-DEP-1.
DEPENDENCY_REVIEW_ACTION = "actions/dependency-review-action"
# Severity thresholds ordered from strictest to most permissive. The guard
# accepts any value at least as strict as `high`.
_ACCEPTABLE_SEVERITIES = {"low", "moderate", "high"}


def check_dependency_review(path: Path) -> list[str]:
    """Assert pr-checks.yml keeps a non-masked dependency-review job.

    Verifies:
      1. A job named `dependency-review` exists.
      2. It uses `actions/dependency-review-action@<any-version>`.
      3. It sets `fail-on-severity` to `low`, `moderate`, or `high`.
      4. The job has no job-level `continue-on-error:` (H1) or `if:` (H2)
         directive — either masks or skips the entire job.
      5. The `Dependency review` step is NOT masked by any
         `continue-on-error:` key, regardless of value — literal `true`
         (N90), expression `${{ ... }}` (H3), or trailing-comment form
         `true  # ...` (H4). All would silently flip a red CVE signal to
         green.

    Inline ` #...` comments are stripped before value comparison so that
    legitimate configs like `fail-on-severity: high  # comment` are not
    flagged as false positives (H5).
    """
    failures: list[str] = []
    text = path.read_text()
    lines = text.splitlines()

    # Locate the `dependency-review:` job block.
    job_start: int | None = None
    for i, raw in enumerate(lines):
        if raw.startswith("  dependency-review:"):
            job_start = i
            break
    if job_start is None:
        failures.append(
            f"{path.name}: `dependency-review` job missing — restores a "
            f"CVSS>=7.0 dependency-CVE gate (CI-DEP-1). Re-add the job "
            f"using {DEPENDENCY_REVIEW_ACTION} with `fail-on-severity: high`."
        )
        return failures

    # Collect the job body (until the next 2-space-indented key or EOF).
    job_body: list[str] = []
    for raw in lines[job_start + 1:]:
        if raw.startswith("  ") and not raw.startswith("    ") and raw.strip() and not raw.lstrip().startswith("#"):
            # Next top-level job key (2-space indent, non-blank, non-comment).
            if raw.endswith(":"):
                break
        job_body.append(raw)

    body_text = "\n".join(job_body)

    if DEPENDENCY_REVIEW_ACTION not in body_text:
        failures.append(
            f"{path.name}: `dependency-review` job does not use "
            f"{DEPENDENCY_REVIEW_ACTION} (CI-DEP-1). Restore the action."
        )

    # Verify fail-on-severity is present and at most `high`. Strip inline
    # ` #...` comments before comparing so that legitimate configs like
    # `fail-on-severity: high  # comment` are not false positives (H5).
    fail_sev = None
    for raw in job_body:
        s = raw.strip()
        if s.startswith("fail-on-severity:"):
            _, _, val = s.partition(":")
            val = val.split(" #", 1)[0].rstrip()
            fail_sev = val.strip().strip("\"'").lower()
            break
    if fail_sev is None:
        failures.append(
            f"{path.name}: `dependency-review` job is missing "
            f"`fail-on-severity:` (CI-DEP-1). Set it to `high` or stricter."
        )
    elif fail_sev not in _ACCEPTABLE_SEVERITIES:
        failures.append(
            f"{path.name}: `dependency-review` job has "
            f"`fail-on-severity: {fail_sev}`; must be one of "
            f"{sorted(_ACCEPTABLE_SEVERITIES)} (CI-DEP-1)."
        )

    # H1 + H2: scan for job-level `continue-on-error:` or `if:` keys at
    # indent 4 (job-body keys). Either directive masks or skips the entire
    # dep-review job, defeating the CI-DEP-1 gate. The check is on key
    # presence alone — any value (true, false, expression, or comment) is
    # suspect because the directive itself is wrong for this job.
    for raw in job_body:
        # Indent exactly 4 (job-body keys), not 6+ (steps / step keys).
        if not raw.startswith("    ") or raw.startswith("      "):
            continue
        s = raw.strip()
        if s.startswith("continue-on-error:"):
            failures.append(
                f"{path.name}: `dependency-review` job has a job-level "
                f"`continue-on-error:` directive (CI-DEP-1/H1). This "
                f"masks the entire job. Remove the directive."
            )
        elif s.startswith("if:"):
            failures.append(
                f"{path.name}: `dependency-review` job has a job-level "
                f"`if:` condition (CI-DEP-1/H2). The dep-review job must "
                f"run unconditionally. Remove the `if:` directive."
            )

    # H3 + H4: detect ANY `continue-on-error:` key on the dependency-review
    # step. Walk the body line-by-line, tracking step boundaries (list items
    # starting with `- `). A step's properties (uses:, continue-on-error:,
    # etc.) may span multiple lines until the next `- ` or end of the job
    # body. Detection is on key presence alone — covers literal `true`
    # (N90), expression `${{ ... }}` (H3), and trailing-comment form
    # `true  # ...` (H4).
    cur_step_lines: list[str] = []
    steps: list[list[str]] = []
    for raw in job_body:
        s = raw.strip()
        if s.startswith("- "):
            if cur_step_lines:
                steps.append(cur_step_lines)
            cur_step_lines = [s]
        elif cur_step_lines:
            # Only accumulate while we're inside a steps: list (indent-based).
            cur_step_lines.append(s)
    if cur_step_lines:
        steps.append(cur_step_lines)

    for step_lines in steps:
        joined = "\n".join(step_lines)
        if DEPENDENCY_REVIEW_ACTION in joined:
            for line in step_lines:
                if line.startswith("continue-on-error:"):
                    _, _, val = line.partition(":")
                    # Strip inline comment so the reported value is clean
                    # (H4: `true  # reason` -> `true`).
                    val = val.split(" #", 1)[0].rstrip().strip()
                    failures.append(
                        f"{path.name}: `dependency-review` step is masked "
                        f"by `continue-on-error:` (value='{val}', "
                        f"N90/CI-DEP-1/H3/H4). This silently flips a red "
                        f"CVE signal to green. Remove the directive."
                    )
                    break

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

    # CI-DEP-1 guard: dependency-review job must remain in pr-checks.yml.
    pr_checks = WORKFLOWS_DIR / "pr-checks.yml"
    if not pr_checks.exists():
        all_failures.append("pr-checks.yml: workflow file missing")
    else:
        all_failures.extend(check_dependency_review(pr_checks))

    if all_failures:
        print("FAIL: CI configuration regression detected:")
        for f in all_failures:
            print("  - " + f)
        return 1

    print("OK: every dorny/paths-filter job has job-level 'pull-requests: read'.")
    print("OK: pr-checks.yml retains a non-masked dependency-review job (CI-DEP-1).")
    for name in targets:
        jobs = _parse_jobs((WORKFLOWS_DIR / name).read_text())
        for jname, info in jobs.items():
            if info["filter"]:
                print(f"  {name} :: {jname} -> {sorted(info['perms'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
