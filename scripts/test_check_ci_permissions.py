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

# CodeRabbit Major (CI-DEP-1 bypass): a COMMENT mentioning the action name
# must NOT satisfy the guard. The old substring check on `body_text` would
# pass here because `actions/dependency-review-action` appears in a comment,
# even though no real `uses:` step exists. Must FAIL.
BYPASS_COMMENT_ONLY = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      # uses: actions/dependency-review-action@v4 — remember to re-add
      - name: Placeholder
        uses: some-other/action@v1
"""

# CodeRabbit Major (CI-DEP-1 bypass): a SIMILAR action name must NOT satisfy
# the guard. `actions/dependency-review-action-foo@v1` is a different action
# (note the `-foo` suffix). The old substring check matched because
# `actions/dependency-review-action` is a prefix. Must FAIL.
BYPASS_SIMILAR_ACTION = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review (fake)
        uses: actions/dependency-review-action-foo@v1
        with:
          fail-on-severity: high
"""

# CodeRabbit Major (CI-DEP-1 bypass): `fail-on-severity` in an UNRELATED step
# must NOT satisfy the guard when the real `uses:` step is absent. The old
# code scanned all of `job_body` for `fail-on-severity`, so an attacker could
# remove the real action step and keep `fail-on-severity` in a placeholder
# step to fool the guard. Must FAIL.
BYPASS_SEVERITY_IN_UNRELATED_STEP = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Some other step
        uses: some-other/action@v1
        with:
          fail-on-severity: high
"""

# REV54 / S1 (quoted-key, single-quote): the EXACT form smuggled into PR #129
# (commit e41f70f). `'continue-on-error': true` is YAML-equivalent to the bare
# `continue-on-error: true` but the pre-REV54 guard's
# `s.startswith("continue-on-error:")` did not match the leading `'`. Must FAIL.
# This test fails on the pre-fix guard (returns [] = no failures) and passes
# after the fix.
REV54_S1_QUOTED_KEY_STEP = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
        # Temporary bypass for DEP-REVIEW-1 as GHAS is not enabled yet
        'continue-on-error': true
"""

# REV54 / S1 (quoted-key, double-quote): `"continue-on-error": true` is the
# double-quoted equivalent. Must FAIL.
REV54_S1_QUOTED_KEY_STEP_DBL = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
        "continue-on-error": true
"""

# REV54 / S1 (quoted-key, job-level): a job-level `'continue-on-error': true`
# (indent 4) must FAIL the H1 check, just like the bare form. The pre-fix
# job-level scan also used `startswith("continue-on-error:")` and missed this.
REV54_S1_QUOTED_KEY_JOB = """\
name: PR Checks
jobs:
  dependency-review:
    'continue-on-error': true
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
"""

# REV54 / S2 (colon-space): `continue-on-error : true` (whitespace before the
# colon) is valid YAML and semantically identical. The pre-fix guard's
# `startswith("continue-on-error:")` did not match. Must FAIL.
REV54_S2_COLON_SPACE_STEP = """\
name: PR Checks
jobs:
  dependency-review:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    steps:
      - uses: actions/checkout@v5
      - name: Dependency review
        uses: actions/dependency-review-action@v4
        with:
          fail-on-severity: high
        continue-on-error : true
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

    def test_bypass_comment_only_fails(self) -> None:
        """CodeRabbit Major: a comment mentioning the action must not satisfy
        the guard. The old substring check on `body_text` passed here."""
        path = _write_yaml(BYPASS_COMMENT_ONLY)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("does not use" in f for f in failures),
            f"expected 'does not use' for comment-only bypass, got: {failures}",
        )

    def test_bypass_similar_action_fails(self) -> None:
        """CodeRabbit Major: a similar action name (e.g. `...-foo@...`) must
        not satisfy the guard. The old substring check matched the prefix."""
        path = _write_yaml(BYPASS_SIMILAR_ACTION)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("does not use" in f for f in failures),
            f"expected 'does not use' for similar-action bypass, got: {failures}",
        )

    def test_bypass_severity_in_unrelated_step_fails(self) -> None:
        """CodeRabbit Major: `fail-on-severity` in an unrelated step must not
        satisfy the guard when the real `uses:` step is absent. The old code
        scanned all of `job_body` for `fail-on-severity`."""
        path = _write_yaml(BYPASS_SEVERITY_IN_UNRELATED_STEP)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("does not use" in f for f in failures),
            f"expected 'does not use' for unrelated-step severity bypass, "
            f"got: {failures}",
        )

    def test_rev54_s1_quoted_key_step_mask_fails(self) -> None:
        """REV54/S1: step-level `'continue-on-error': true` (the exact PR #129
        form) must FAIL. The pre-fix guard's `startswith("continue-on-error:")`
        did not match the leading single-quote, so this returned [] (bypass).
        """
        path = _write_yaml(REV54_S1_QUOTED_KEY_STEP)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("continue-on-error" in f and "N90" in f for f in failures),
            f"expected S1 quoted-key step continue-on-error failure, got: {failures}",
        )

    def test_rev54_s1_quoted_key_step_value_extracted(self) -> None:
        """REV54/S1: the guard must not only detect the quoted key but also
        extract its value (`true`) for the failure message, proving the value
        extraction (partition on ':') still works through the quote."""
        path = _write_yaml(REV54_S1_QUOTED_KEY_STEP)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("value='true'" in f for f in failures),
            f"expected value='true' in S1 quoted-key failure, got: {failures}",
        )

    def test_rev54_s1_double_quoted_key_step_mask_fails(self) -> None:
        """REV54/S1: step-level `"continue-on-error": true` (double-quoted key)
        must FAIL too — the fix handles both quote styles."""
        path = _write_yaml(REV54_S1_QUOTED_KEY_STEP_DBL)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("continue-on-error" in f and "N90" in f for f in failures),
            f"expected S1 double-quoted-key step failure, got: {failures}",
        )

    def test_rev54_s1_quoted_key_job_level_fails(self) -> None:
        """REV54/S1: a job-level `'continue-on-error': true` (indent 4) must
        FAIL the H1 check. The pre-fix job-level scan also used
        `startswith("continue-on-error:")` and missed the quoted form."""
        path = _write_yaml(REV54_S1_QUOTED_KEY_JOB)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("H1" in f and "job-level" in f for f in failures),
            f"expected H1 job-level quoted-key failure, got: {failures}",
        )

    def test_rev54_s2_colon_space_step_mask_fails(self) -> None:
        """REV54/S2: step-level `continue-on-error : true` (whitespace before
        the colon) is valid YAML and must FAIL. The pre-fix guard's
        `startswith("continue-on-error:")` did not match the space before ':'.
        """
        path = _write_yaml(REV54_S2_COLON_SPACE_STEP)
        failures = m.check_dependency_review(path)
        self.assertTrue(
            any("continue-on-error" in f and "N90" in f for f in failures),
            f"expected S2 colon-space step continue-on-error failure, got: {failures}",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
