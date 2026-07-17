#!/usr/bin/env python3
"""Unit tests for `scripts/check_ci_permissions.py`.

Covers the CI-DEP-1 regression guard added for the dependency-review removal
regression found in PRs #79 / #82 (see `docs/ISSUES.md` CI-DEP-1).

Run: `python3 scripts/test_check_ci_permissions.py`
Pure stdlib; no PyYAML / pytest dependency.
"""
from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

# Load the module under test by file path so it can be invoked from anywhere.
_SCRIPT = Path(__file__).resolve().parent / "check_ci_permissions.py"
_spec = importlib.util.spec_from_file_location("check_ci_permissions", _SCRIPT)
assert _spec is not None and _spec.loader is not None
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)


def _write_yaml(text: str) -> Path:
    """Write `text` to a temp file and return its Path."""
    fd = tempfile.NamedTemporaryFile(
        mode="w", suffix=".yml", delete=False, encoding="utf-8"
    )
    fd.write(text)
    fd.close()
    return Path(fd.name)


# Baseline dev pr-checks.yml — must PASS the guard.
DEV_BASELINE = """\
name: PR Checks
on:
  pull_request:
    branches: [main, dev]
permissions:
  contents: read
  pull-requests: write  # dependency-review needs this
jobs:
  commitlint:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# PR #79 / #82 regression: dependency-review job deleted.
PR79_82_REGRESSION = """\
name: PR Checks
on:
  pull_request:
    branches: [main, dev]
permissions:
  contents: read
  pull-requests: read
jobs:
  commitlint:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
"""

# N90 regression: dep-review step masked by continue-on-error: true.
N90_MASKED = """\
name: PR Checks
on:
  pull_request:
    branches: [main, dev]
permissions:
  contents: read
  pull-requests: read
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        continue-on-error: true
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# Action swapped out — must FAIL.
WRONG_ACTION = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: some-other/action@v1
        with:
          fail-on-severity: high
"""

# fail-on-severity missing — must FAIL.
MISSING_SEVERITY = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
"""

# fail-on-severity: critical — must FAIL (critical only catches CVSS>=9.0,
# too permissive for our CVSS>=7.0 security gate).
CRITICAL_SEVERITY = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: critical
"""

# fail-on-severity: moderate — must PASS (stricter than high).
MODERATE_SEVERITY = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: moderate
"""

# Different action version (v5) — must PASS (any version is OK).
ACTION_V5 = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v5
        with:
          fail-on-severity: high
"""

# H1: job-level `continue-on-error: true` masks the entire dep-review job —
# must FAIL. The directive sits at indent 4 (job-body key), before `steps:`.
H1_JOB_LEVEL_COE = """\
name: PR Checks
jobs:
  dependency-review:
    continue-on-error: true
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# H2: job-level `if: false` skips the dep-review job — must FAIL.
H2_IF_FALSE = """\
name: PR Checks
jobs:
  dependency-review:
    if: false
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# H3: step-level `continue-on-error: ${{ ... }}` expression form — must FAIL.
# The previous guard only matched the literal `true` and missed expressions.
H3_EXPR_COE = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        continue-on-error: ${{ matrix.flag }}
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# H4: step-level `continue-on-error: true  # comment` trailing-comment form —
# must FAIL. The previous guard compared the raw value and missed the comment.
H4_TRAILING_COMMENT_COE = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        continue-on-error: true  # rationale here
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# H5: `fail-on-severity: high  # comment` is a legitimate config — must PASS.
# The previous guard treated the raw value as `high  # comment` and rejected
# it as an unknown severity (false positive).
H5_FAIL_ON_SEV_WITH_COMMENT = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high  # CVSS>=7.0 gate
"""


class CheckDependencyReviewTests(unittest.TestCase):
    def test_dev_baseline_passes(self) -> None:
        """The current dev pr-checks.yml must pass the guard."""
        path = _write_yaml(DEV_BASELINE)
        self.assertEqual(m.check_dependency_review(path), [])

    def test_pr79_pr82_regression_fails(self) -> None:
        """PR #79 / #82 remove the dependency-review job — must fail."""
        path = _write_yaml(PR79_82_REGRESSION)
        failures = m.check_dependency_review(path)
        self.assertEqual(len(failures), 1)
        self.assertIn("dependency-review` job missing", failures[0])
        self.assertIn("CI-DEP-1", failures[0])

    def test_n90_continue_on_error_fails(self) -> None:
        """Masking the dep-review step with continue-on-error must fail (N90)."""
        path = _write_yaml(N90_MASKED)
        failures = m.check_dependency_review(path)
        self.assertEqual(len(failures), 1)
        self.assertIn("continue-on-error", failures[0])
        self.assertIn("N90", failures[0])

    def test_wrong_action_fails(self) -> None:
        """A non-dependency-review-action must fail."""
        path = _write_yaml(WRONG_ACTION)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("does not use" in f for f in failures),
            f"expected 'does not use' failure, got: {failures}",
        )

    def test_missing_fail_on_severity_fails(self) -> None:
        """Missing fail-on-severity must fail (would default to low — but we
        require explicit declaration to prevent silent drift)."""
        path = _write_yaml(MISSING_SEVERITY)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("fail-on-severity" in f for f in failures),
            f"expected fail-on-severity failure, got: {failures}",
        )

    def test_critical_severity_fails(self) -> None:
        """fail-on-severity: critical is too permissive (CVSS>=9.0 only)."""
        path = _write_yaml(CRITICAL_SEVERITY)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("critical" in f for f in failures),
            f"expected 'critical' rejection, got: {failures}",
        )

    def test_moderate_severity_passes(self) -> None:
        """fail-on-severity: moderate is stricter than high — must pass."""
        path = _write_yaml(MODERATE_SEVERITY)
        self.assertEqual(m.check_dependency_review(path), [])

    def test_action_v5_passes(self) -> None:
        """Any action version (v4, v5, ...) is accepted."""
        path = _write_yaml(ACTION_V5)
        self.assertEqual(m.check_dependency_review(path), [])

    def test_h1_job_level_continue_on_error_fails(self) -> None:
        """H1: job-level `continue-on-error: true` masks the whole job."""
        path = _write_yaml(H1_JOB_LEVEL_COE)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("H1" in f and "job-level" in f for f in failures),
            f"expected H1 job-level continue-on-error failure, got: {failures}",
        )

    def test_h2_if_false_skips_job_fails(self) -> None:
        """H2: `if: false` (or any `if:`) conditionally skips the job."""
        path = _write_yaml(H2_IF_FALSE)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("H2" in f and "`if:`" in f for f in failures),
            f"expected H2 job-level if: failure, got: {failures}",
        )

    def test_h3_expression_continue_on_error_fails(self) -> None:
        """H3: `continue-on-error: ${{ ... }}` expression must fail."""
        path = _write_yaml(H3_EXPR_COE)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("H3" in f and "continue-on-error" in f for f in failures),
            f"expected H3 expression continue-on-error failure, got: {failures}",
        )

    def test_h4_trailing_comment_continue_on_error_fails(self) -> None:
        """H4: `continue-on-error: true  # reason` must fail (comment stripped)."""
        path = _write_yaml(H4_TRAILING_COMMENT_COE)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("H4" in f and "continue-on-error" in f for f in failures),
            f"expected H4 trailing-comment continue-on-error failure, got: {failures}",
        )

    def test_h5_trailing_comment_on_fail_on_severity_passes(self) -> None:
        """H5: `fail-on-severity: high  # comment` must PASS (no false positive)."""
        path = _write_yaml(H5_FAIL_ON_SEV_WITH_COMMENT)
        self.assertEqual(
            m.check_dependency_review(path),
            [],
            "fail-on-severity with inline comment must not be a false positive (H5)",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
