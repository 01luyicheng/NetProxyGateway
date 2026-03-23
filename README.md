# NetProxyGateway

**AI-first Android project** — documentation is intentionally minimized for agent execution.

## 🎯 For AI Agents Starting Work

If you are Claude Code working on this project:

1. **Execution authority**: Read [docs/CLAUDE.md](docs/CLAUDE.md) first — it is the only authoritative guide
2. **Current scope**: Android client only (no server code in this repo)
3. **Quick commands**:
   - Build: `android\gradlew.bat -p android assembleDebug --stacktrace --no-daemon`
   - Tests: `android\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon`
4. **Code entrypoint**: [android/app/src/main/java/com/netproxy/gateway/](android/app/src/main/java/com/netproxy/gateway/)

## 📂 Repository Structure

```
android/                          # Gradle Android project
├── app/
│   ├── src/main/java/.../       # Runtime code (source of truth)
│   ├── src/test/                # Unit tests
│   └── build.gradle.kts         # App-level config
├── build.gradle.kts             # Project-level config
└── gradle/wrapper/              # Gradle 9.2.1 (pinned)

docs/
├── CLAUDE.md                    # Execution contract (required reading)
└── README.md                    # Documentation policy
```

## 🔗 Documentation Hierarchy

- **Decisions to make?** → [docs/CLAUDE.md](docs/CLAUDE.md)
- **Why minimal docs?** → [docs/README.md](docs/README.md)
- **Need build help?** → See commands above
- **Question not answered?** → Check code, then update [docs/CLAUDE.md](docs/CLAUDE.md)

## 📋 Current Scope

- Repository contains Android client only
- No `server/` implementation exists in this repository
- Primary objective: stabilize Android behavior and test coverage
- Server-side work is documented but blocked (see [docs/CLAUDE.md](docs/CLAUDE.md) section 2)

## ⚙️ Build & Verification

Run on Windows (required for reproducibility):

```bash
# Build
android\gradlew.bat -p android assembleDebug --stacktrace --no-daemon

# Unit tests (verification gate)
android\gradlew.bat -p android :app:testDebugUnitTest --stacktrace --no-daemon

# For IDE: import the `android/` folder into Android Studio
```

If build fails locally with Gradle version errors, use the pinned wrapper (do not use system Gradle).

## 📄 License

MIT — see [LICENSE](LICENSE)

---

**Last Updated**: 2026-03-23 | **Status**: Stable | **Next Agent Review**: 2027-03-23