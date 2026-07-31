#!/usr/bin/env python3
"""Tests for the Makefile `go mod tidy` drift-check exit-code semantics.

Regression guard for the silent no-op bug fixed in REV31 (PR #123) — see
`docs/ISSUES.md` entry MAKEFILE-TIDY-NOOP-1.

The bug
-------
In the `go-ci-component` and `go-quality-component` Makefile targets, the
drift-check subshell used to be::

    (cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; \
     git checkout -- go.mod go.sum 2>/dev/null || true)

A POSIX subshell `(cmd1; cmd2)` returns the exit code of the *last* command.
Here the last command is `git checkout ... || true`, which always returns 0.
The non-zero exit code from `git diff --exit-code` (set when `go mod tidy`
modified the files, i.e. they were untidy) was therefore discarded, and the
following `&& echo "...passed"` always ran. Result: untidy `go.mod`/`go.sum`
silently passed the drift check.

The fix
-------
Capture `git diff --exit-code`'s exit code into `rc` *before* the cleanup
`git checkout` runs, then `exit $$rc` so the `&&` chain short-circuits on
drift::

    (cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; rc=$$?; \
     git checkout -- go.mod go.sum 2>/dev/null || true; \
     if [ $$rc -ne 0 ]; then echo "FAIL: $$dir has untidy go.mod/go.sum"; fi; exit $$rc)

Run: ``python3 scripts/test_makefile_tidy_check.py``
Pure stdlib; no network, no Go toolchain required.
"""
from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MAKEFILE = REPO_ROOT / "Makefile"

# Anchors that MUST appear in the Makefile recipes after the fix.
# We look for `rc=$$?` on the line immediately following a
# `git diff --exit-code -- go.mod go.sum` line, and `exit $$rc` before the
# closing `)` of the same subshell.
_DRIFT_LINE_RE = re.compile(r"git diff --exit-code -- go\.mod go\.sum")


def _extract_recipe(makefile_text: str, target_name: str) -> str:
    """Return the body of a Makefile target (recipe lines, leading tab stripped)."""
    lines = makefile_text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.startswith(f"{target_name}:"):
            start = i + 1
            break
    if start is None:
        raise AssertionError(f"Target {target_name!r} not found in Makefile")
    body: list[str] = []
    for line in lines[start:]:
        if line == "":
            # blank line ends the recipe only if the next non-blank line
            # is not a continuation (tab-prefixed)
            break
        if not line.startswith("\t"):
            break
        body.append(line[1:])  # strip leading tab
    return "\n".join(body)


def _run_subshell(snippet: str, cwd: str) -> int:
    """Run a shell snippet wrapped in a subshell `( ... )` and return exit code."""
    return subprocess.run(
        ["bash", "-c", f"( {snippet} )"],
        cwd=cwd,
        capture_output=True,
        text=True,
    ).returncode


def _setup_repo_with_committed_go_mod(path: Path) -> None:
    """Init a git repo and commit a tidy `go.mod` + empty `go.sum`."""
    subprocess.run(["git", "init", "-q"], cwd=path, check=True, capture_output=True)
    subprocess.run(
        ["git", "config", "user.email", "test@example.com"],
        cwd=path, check=True, capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Test"],
        cwd=path, check=True, capture_output=True,
    )
    (path / "go.mod").write_text("module example.com/test\n\ngo 1.21\n")
    (path / "go.sum").write_text("")
    subprocess.run(["git", "add", "-A"], cwd=path, check=True, capture_output=True)
    subprocess.run(
        ["git", "commit", "-q", "-m", "init"],
        cwd=path, check=True, capture_output=True,
    )


# Mirrors the FIXED Makefile recipe (with rc capture and exit $rc). Keep in
# sync with the `go-ci-component` / `go-quality-component` targets.
_FIXED_SNIPPET = (
    "git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; rc=$?; "
    "git checkout -- go.mod go.sum 2>/dev/null || true; "
    'if [ $rc -ne 0 ]; then echo "FAIL: has untidy go.mod/go.sum"; fi; '
    "exit $rc"
)

# Mirrors the OLD buggy Makefile recipe (no rc capture; last command is
# `|| true`, so the subshell always returns 0). Kept for documentation.
_BUGGY_SNIPPET = (
    "git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; "
    "git checkout -- go.mod go.sum 2>/dev/null || true"
)

_UNTIDY_GO_MOD = (
    "module example.com/test\n\ngo 1.21\n\n"
    "require (\n\tgithub.com/google/uuid v1.6.0\n)\n"
)


class TestMakefileTidyCheck(unittest.TestCase):
    """Verify the Makefile drift-check is no longer a silent no-op."""

    def setUp(self) -> None:
        self.makefile_text = MAKEFILE.read_text()

    # -- Makefile content guards (regression prevention) --

    def test_go_ci_component_uses_rc_capture(self) -> None:
        recipe = _extract_recipe(self.makefile_text, "go-ci-component")
        self.assertIn(
            "rc=$$?", recipe,
            "go-ci-component: `rc=$$?` capture not found — drift check is a no-op "
            "(MAKEFILE-TIDY-NOOP-1 regression).",
        )
        self.assertIn(
            "exit $$rc", recipe,
            "go-ci-component: `exit $$rc` not found — drift-check exit code is masked "
            "by the trailing `git checkout ... || true`.",
        )

    def test_go_quality_component_uses_rc_capture(self) -> None:
        recipe = _extract_recipe(self.makefile_text, "go-quality-component")
        self.assertIn(
            "rc=$$?", recipe,
            "go-quality-component: `rc=$$?` capture not found — drift check is a no-op "
            "(MAKEFILE-TIDY-NOOP-1 regression).",
        )
        self.assertIn(
            "exit $$rc", recipe,
            "go-quality-component: `exit $$rc` not found — drift-check exit code is "
            "masked by the trailing `git checkout ... || true`.",
        )

    def test_no_buggy_drift_check_pattern_remains(self) -> None:
        """Every `git diff --exit-code -- go.mod go.sum` in the Makefile must
        be followed (within 2 lines) by EITHER `rc=$$?` capture OR an
        `if [ $$? -ne 0 ]` guard. The buggy form — same subshell with a
        trailing `git checkout ... || true)` that masks the exit code — must
        NOT appear anywhere.

        Two acceptable patterns:

        1. rc-capture (used by ``go-ci-component`` / ``go-quality-component``)::

               (cd $$dir && git diff --exit-code ...; rc=$$?;
                git checkout ... || true; ...; exit $$rc)

        2. immediate-if (used by ``go-mod-tidy-check``)::

               (cd $$dir && git diff --exit-code ...);
               if [ $$? -ne 0 ]; then ...
        """
        lines = self.makefile_text.splitlines()
        buggy_sites: list[int] = []
        for i, line in enumerate(lines):
            if not _DRIFT_LINE_RE.search(line):
                continue
            # Look at the next 2 lines for an acceptable exit-code guard.
            window = "\n".join(lines[i : i + 3])
            has_rc_capture = "rc=$$?" in window
            has_if_guard = "if [ $$? -ne 0 ]" in window
            if not has_rc_capture and not has_if_guard:
                buggy_sites.append(i + 1)
        self.assertEqual(
            buggy_sites, [],
            f"MAKEFILE-TIDY-NOOP-1 regression: `git diff --exit-code -- go.mod go.sum` "
            f"at line(s) {buggy_sites} is NOT followed by `rc=$$?` capture or "
            f"`if [ $$? -ne 0 ]` guard within 2 lines. The drift check is a "
            f"silent no-op at these sites.",
        )

    # -- Behavioural tests (shell exit-code semantics) --

    def test_fixed_snippet_fails_on_untidy_go_mod(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _setup_repo_with_committed_go_mod(d)
            # Make go.mod untidy (uncommitted modification → git diff returns 1)
            (d / "go.mod").write_text(_UNTIDY_GO_MOD)
            rc = _run_subshell(_FIXED_SNIPPET, str(d))
            self.assertNotEqual(
                rc, 0,
                "Fixed drift-check must FAIL (non-zero) when go.mod is untidy.",
            )

    def test_fixed_snippet_passes_on_tidy_go_mod(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _setup_repo_with_committed_go_mod(d)
            rc = _run_subshell(_FIXED_SNIPPET, str(d))
            self.assertEqual(
                rc, 0,
                "Fixed drift-check must PASS (zero) when go.mod is tidy.",
            )

    def test_buggy_snippet_silently_passes_on_untidy_go_mod(self) -> None:
        """Documentation test: demonstrates the original bug. If this test
        ever FAILS (i.e. rc != 0), the buggy snippet was corrected — update
        this test to assert the corrected behaviour and remove the snippet.
        """
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            _setup_repo_with_committed_go_mod(d)
            (d / "go.mod").write_text(_UNTIDY_GO_MOD)
            rc = _run_subshell(_BUGGY_SNIPPET, str(d))
            self.assertEqual(
                rc, 0,
                "Buggy drift-check incorrectly returned 0 on untidy go.mod. "
                "This documents the MAKEFILE-TIDY-NOOP-1 defect.",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
