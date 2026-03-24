# CLAUDE.md

This file is the only execution contract for AI agents in this repository.

## 1) Objective (Current Phase)

Deliver reliable Android behavior for remote network assistance workflows.
Do not expand product scope during this phase.

## 2) Reality Constraints

- Repository has Android client code only.
- `server/` directory does not exist in this repository.
- If a requirement depends on server implementation, treat it as blocked and record the gap.

## 3) Source of Truth

- Runtime behavior: Kotlin code under `android/app/src/main/java/com/netproxy/gateway/`.
- Build configuration: `android/build.gradle.kts`, `android/app/build.gradle.kts`, `android/gradle/wrapper/gradle-wrapper.properties`.
- Tests: `android/app/src/test/`.

If docs conflict with code, trust code and update this file.

## 4) Allowed and Forbidden Actions

Allowed:

- Fix Android defects.
- Add or update Android unit tests.
- Refactor Android internals when behavior is preserved or covered by tests.

Forbidden:

- Fabricating non-existent modules, files, or server status.
- Writing speculative architecture docs.
- Keeping duplicate design docs that are not required for next implementation steps.

## 5) Current Technical Risks

- WiFi connection path uses legacy APIs that are restricted on Android 10+.
- MQTT security currently uses TLS 1.2 with default trust manager; no pinning.
- Any claims beyond those two points must be verified in code first.

## 6) Execution Priority Queue

1. Keep build and targeted tests green on Gradle wrapper.
2. Fix correctness bugs affecting connection, VPN, proxy, WiFi, and UI state flow.
3. Increase test coverage around changed logic before broad refactors.
4. Defer server-side work until repository contains server code.

## 7) Verification Gate (Must Pass)

- Build: `android\\gradlew.bat -p android assembleDebug --stacktrace --no-daemon`
- Unit tests: `android\\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon`
- For targeted changes, run the narrowest relevant test first, then the broader suite if needed.

## 8) Output Format for AI Changes

- Start with what changed and why.
- Include impacted file paths.
- Include verification commands actually run and outcome.
- Explicitly state unresolved blockers.

## 9) Document Minimalism Rule

Keep only docs that alter AI execution decisions.
Delete stale, duplicate, or aspirational documents to reduce context noise.

## 10) Multi-Agent Collaboration Standards

When multiple AI agents work in parallel or sequentially:

- Each agent must read this CLAUDE.md before starting work
- No inter-agent communication; all constraints flow downward via this document
- On conflicts between agents' changes, most recent commit to this file wins
- All agents merge changes via git pull requests (never force-push)
- If two agents modify the same code file, the second agent must rebase after pulling main

## 11) Annual Documentation Audit (Maintenance Protocol)

Every 12 months, perform a health review:

1. Remove resolved Technical Risks (section 5) once verified complete in production
2. Verify Objective still aligns with product reality; update if pivoting
3. Update Reality Constraints with new Android OS restrictions or deprecated APIs
4. Archive completed priority queue items to git commit history only (no living docs)
5. Enforce CLAUDE.md ceiling: keep under 400 lines total

Rule: Anything older than 6 months and already resolved should live in code comments
or test files, not in this living document. This ensures context stays focused on
current work only.

## 12) Verification Tracking (For Continuity)

To maintain agent continuity across sessions:

- Each merged commit that passes verification must note the agent name and date
- Before starting work, agent must verify: `android\gradlew.bat -p android :app:testDebugUnitTest` passes
- If main branch tests fail, unblock by filing clear blockers in section 5 (Technical Risks)

