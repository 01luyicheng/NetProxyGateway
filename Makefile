.PHONY: help
help:
	@echo "NetProxyGateway Build Tool"
	@echo ""
	@echo "Android Targets:"
	@echo "  android-build          Build Android Debug APK"
	@echo "  android-test           Run Android unit tests (must pass)"
	@echo "  android-lint           Run Android Lint"
	@echo "  android-coverage       Generate JaCoCo coverage report"
	@echo "  android-dep-check      Run dependency vulnerability check"
	@echo "  android-all            Full Android checks (build+test+lint+coverage+dep)"
	@echo ""
	@echo "Go Server Targets:"
	@echo "  go-build               Build all Go server components"
	@echo "  go-test                Test all Go server components"
	@echo "  go-fmt                 Check gofmt on all Go components"
	@echo "  go-vet                 Run go vet on all Go components"
	@echo "  go-mod-tidy-check      Verify go.mod/go.sum are tidy"
	@echo "  go-test-race           Run Go tests with race detector"
	@echo "  go-coverage-check      Check test coverage thresholds"
	@echo "  go-vuln                Run Go vulnerability scan"
	@echo "  go-ci                  CI target: build+test+fmt+vet+mod-tidy-check"
	@echo "  go-ci-component        CI matrix target (COMPONENT=xxx)"
	@echo "  go-build-component     CI matrix: go build only, HARD GATE (COMPONENT=xxx)"
	@echo "  go-test-component      CI matrix: go test only, HARD GATE (COMPONENT=xxx)"
	@echo "  go-quality-component   CI matrix: fmt+vet+tidy, masked in CI (COMPONENT=xxx)"
	@echo "  go-all                 All Go checks (build+test+fmt+vet+vuln)"
	@echo "  api-smoke              Build API (CGO=1) and verify /health returns db_status:ok"
	@echo ""
	@echo "Docker Targets:"
	@echo "  docker-up              Start all services (docker-compose)"
	@echo "  docker-down            Stop all services"
	@echo "  docker-build           Build all Docker images"
	@echo ""
	@echo "CI Config Guards:"
	@echo "  ci-perms-check         Verify paths-filter jobs have pull-requests:read"
	@echo ""
	@echo "Combined Targets:"
	@echo "  all                    Run Android and Go full build & test"
	@echo "  test                   Run Android and Go tests"
	@echo "  clean                  Clean build artifacts"

# ---------------------------------------------------------------------------
# Android
# ---------------------------------------------------------------------------

# Cross-platform Gradle wrapper: use .bat on Windows, shell script elsewhere
ifeq ($(OS),Windows_NT)
GRADLEW := gradlew.bat
else
GRADLEW := ./gradlew
endif

ANDROID_DIR := android

# CI environment detection: append --no-daemon in CI
ifdef CI
GRADLE_FLAGS := --stacktrace --build-cache --parallel --no-daemon
else
GRADLE_FLAGS := --stacktrace --build-cache --parallel
endif

android-build:
	cd $(ANDROID_DIR) && $(GRADLEW) assembleDebug $(GRADLE_FLAGS)

# test must pass — no --continue here
android-test:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:testDebugUnitTest $(GRADLE_FLAGS)

android-lint:
	cd $(ANDROID_DIR) && $(GRADLEW) lintDebug $(GRADLE_FLAGS)

android-coverage:
	cd $(ANDROID_DIR) && $(GRADLEW) jacocoTestReport $(GRADLE_FLAGS)

android-dep-check:
	cd $(ANDROID_DIR) && $(GRADLEW) dependencyCheckAnalyze $(GRADLE_FLAGS)

# android-all: test must pass (step 1), then lint+coverage+dep-check (step 2).
# --continue only in step 2: lint/dep-check failures should not mask test results,
# but test failures must stop the pipeline immediately.
android-all: android-build android-test
	cd $(ANDROID_DIR) && $(GRADLEW) jacocoTestReport lintDebug dependencyCheckAnalyze $(GRADLE_FLAGS) --continue
	@echo "Android full checks complete"

# ---------------------------------------------------------------------------
# Go Server
# ---------------------------------------------------------------------------

GO_SERVER_COMPONENTS := api socks5-proxy tunnel
GO_SHARED_COMPONENTS := httpclient ratelimit recovery stringutil
GO_COMPONENTS := $(GO_SERVER_COMPONENTS) $(GO_SHARED_COMPONENTS)

# Shell snippet to resolve component directory (shared modules live under server/shared/)
# Usage: prefix each recipe with $(COMPONENT_PATH_FN) then call $$comp_path <component>
COMPONENT_PATH_FN = comp_path() { case "$$1" in httpclient|ratelimit|recovery|stringutil) echo "server/shared/$$1" ;; *) echo "server/$$1" ;; esac; };

go-build:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Building $$dir ..." && \
		(cd $$dir && go build -v ./...) || exit 1; \
	done

go-test:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Testing $$dir ..." && \
		(cd $$dir && go test -v -coverprofile=coverage.out ./...) || exit 1; \
	done

go-fmt:
	@$(COMPONENT_PATH_FN) \
	fail=0; \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Checking gofmt $$dir ..." && \
		out=$$(cd $$dir && find . -name '*.go' -not -path '*/vendor/*' -exec gofmt -l {} + 2>/dev/null); \
		if [ -n "$$out" ]; then \
			echo "FAIL: gofmt found unformatted files in $$dir:"; \
			echo "$$out"; \
			fail=1; \
		fi; \
	done; \
	exit $$fail

go-vet:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Vetting $$dir ..." && \
		(cd $$dir && go vet ./...) || exit 1; \
	done

# go-mod-tidy-check: verify go.mod/go.sum are tidy without modifying files.
# NOTE: stash or commit changes before running locally.
go-mod-tidy-check:
	@$(COMPONENT_PATH_FN) \
	fail=0; \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Checking mod tidy $$dir ..." && \
		(cd $$dir && go mod tidy) && \
		(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1); \
		if [ $$? -ne 0 ]; then \
			echo "FAIL: $$dir has untidy go.mod/go.sum"; \
			fail=1; \
		fi; \
		(cd $$dir && git checkout -- go.mod go.sum 2>/dev/null || true); \
	done; \
	exit $$fail

go-test-race:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Race-testing $$dir ..." && \
		(cd $$dir && go test -race -v ./...) || exit 1; \
	done

go-coverage-check:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Coverage $$dir ..." && \
		(cd $$dir && go test -coverprofile=coverage.out ./... && \
		 go tool cover -func=coverage.out | tail -1) || exit 1; \
	done

go-vuln:
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		echo "Scanning $$dir ..." && \
		(cd $$dir && govulncheck ./...) || exit 1; \
	done

# CI target: build + test + fmt + vet + mod-tidy-check (no vuln scan in CI hot path)
go-ci: go-build go-test go-fmt go-vet go-mod-tidy-check
	@echo "Go CI checks complete"

# CI matrix target: build+test+fmt+vet+mod-tidy-check for a single component.
# Usage: make go-ci-component COMPONENT=api
# NOTE: Kept for backwards compatibility / local aggregate use. The CI workflow
# (go-ci.yml) intentionally calls the three split targets below so that
# `go build` and `go test` are HARD GATES (no continue-on-error), while only
# the quality checks (fmt/vet/tidy) remain masked per ADR-006. See
# docs/ISSUES.md CI-MASK-1 for the regression this split fixes.
go-ci-component:
	@if [ -z "$(COMPONENT)" ]; then echo "ERROR: COMPONENT is required"; exit 1; fi
	@$(COMPONENT_PATH_FN) \
	dir=$$(comp_path $(COMPONENT)); \
	echo "=== CI checks for $$dir ===" && \
	(cd $$dir && go build -v ./...) && \
	(cd $$dir && go test -v -coverprofile=coverage.out ./...) && \
	out=$$(cd $$dir && find . -name '*.go' -not -path '*/vendor/*' -exec gofmt -l {} + 2>/dev/null); \
	if [ -n "$$out" ]; then \
		echo "FAIL: gofmt found unformatted files:"; echo "$$out"; exit 1; \
	fi && \
	(cd $$dir && go vet ./...) && \
	(cd $$dir && go mod tidy) && \
	(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; \
	 git checkout -- go.mod go.sum 2>/dev/null || true) && \
	echo "=== $$dir CI checks passed ==="

# CI matrix target: go build for a single component (HARD GATE — no masking).
# Usage: make go-build-component COMPONENT=api
go-build-component:
	@if [ -z "$(COMPONENT)" ]; then echo "ERROR: COMPONENT is required"; exit 1; fi
	@$(COMPONENT_PATH_FN) \
	dir=$$(comp_path $(COMPONENT)); \
	echo "=== go build for $$dir ===" && \
	(cd $$dir && go build -v ./...) && \
	echo "=== $$dir build passed ==="

# CI matrix target: go test for a single component (HARD GATE — no masking).
# Generates coverage.out in the component directory for artifact upload.
# Usage: make go-test-component COMPONENT=api
go-test-component:
	@if [ -z "$(COMPONENT)" ]; then echo "ERROR: COMPONENT is required"; exit 1; fi
	@$(COMPONENT_PATH_FN) \
	dir=$$(comp_path $(COMPONENT)); \
	echo "=== go test for $$dir ===" && \
	(cd $$dir && go test -v -coverprofile=coverage.out ./...) && \
	echo "=== $$dir test passed ==="

# CI matrix target: quality checks (gofmt + vet + mod-tidy) for a single component.
# Masked with continue-on-error in go-ci.yml per ADR-006 (baseline collection).
# Usage: make go-quality-component COMPONENT=api
go-quality-component:
	@if [ -z "$(COMPONENT)" ]; then echo "ERROR: COMPONENT is required"; exit 1; fi
	@$(COMPONENT_PATH_FN) \
	dir=$$(comp_path $(COMPONENT)); \
	echo "=== quality checks for $$dir ===" && \
	out=$$(cd $$dir && find . -name '*.go' -not -path '*/vendor/*' -exec gofmt -l {} + 2>/dev/null); \
	if [ -n "$$out" ]; then \
		echo "FAIL: gofmt found unformatted files:"; echo "$$out"; exit 1; \
	fi && \
	(cd $$dir && go vet ./...) && \
	(cd $$dir && go mod tidy) && \
	(cd $$dir && git diff --exit-code -- go.mod go.sum >/dev/null 2>&1; \
	 git checkout -- go.mod go.sum 2>/dev/null || true) && \
	echo "=== $$dir quality checks passed ==="

# ---------------------------------------------------------------------------
# CI workflow configuration guards
# ---------------------------------------------------------------------------

# Regression guard for the dorny/paths-filter `pull-requests: read` permission
# bug: a job-level `permissions:` block REPLACES (not merges with) the
# workflow-level block, so the grant must be present at the job level for every
# job that runs dorny/paths-filter, otherwise the required CI check fails on
# every PR with "Resource not accessible by integration" (HTTP 403).
ci-perms-check:
	@python3 scripts/check_ci_permissions.py

# ---------------------------------------------------------------------------
# Docker
# ---------------------------------------------------------------------------

docker-up:
	docker-compose -f server/docker-compose.yml up -d

docker-down:
	docker-compose -f server/docker-compose.yml down

docker-build:
	docker-compose -f server/docker-compose.yml build

# Smoke test: build the API with the same CGO flag as server/api/Dockerfile
# (CGO_ENABLED=1) and assert /health returns db_status:"ok", which proves the
# go-sqlite3 driver is functional (not the CGO_ENABLED=0 stub that crashes on
# startup). Guards against re-introducing the Dockerfile CGO regression.
api-smoke:
	@set -e; \
	tmpdir=$$(mktemp -d); \
	trap 'rm -rf "$$tmpdir"; if [ -n "$$PID" ]; then kill "$$PID" 2>/dev/null || true; fi' EXIT; \
	cd server/api; \
	echo "==> Building api with CGO_ENABLED=1 (mirrors server/api/Dockerfile)"; \
	CGO_ENABLED=1 GOOS=linux go build -o "$$tmpdir/api" .; \
	echo "==> Starting api on :18090"; \
	DB_PATH="$$tmpdir/api.db" JWT_SECRET=0123456789abcdef0123456789abcdef \
		INTERNAL_API_KEY=smoke-key ADMIN_USER=admin ADMIN_PASS=password123 \
		PORT=18090 "$$tmpdir/api" & \
	PID=$$!; \
	for i in $$(seq 1 30); do \
		if curl -sf --noproxy '*' http://127.0.0.1:18090/health >/dev/null 2>&1; then break; fi; \
		if ! kill -0 "$$PID" 2>/dev/null; then echo "FAIL: api exited before becoming healthy"; exit 1; fi; \
		sleep 0.5; \
	done; \
	resp=$$(curl -sf --noproxy '*' http://127.0.0.1:18090/health); \
	echo "==> /health: $$resp"; \
	echo "$$resp" | grep -q '"db_status":"ok"' || { echo "FAIL: db_status is not ok (sqlite driver not functional)"; exit 1; }; \
	echo "PASS: api starts with CGO build and sqlite driver is functional (db_status:ok)."

# ---------------------------------------------------------------------------
# Combined
# ---------------------------------------------------------------------------

go-all: go-build go-test go-fmt go-vet go-vuln
	@echo "Go full checks complete"

all: android-all go-all

test: android-test go-test

clean:
	cd $(ANDROID_DIR) && $(GRADLEW) clean $(GRADLE_FLAGS)
	@$(COMPONENT_PATH_FN) \
	for component in $(GO_COMPONENTS); do \
		dir=$$(comp_path $$component); \
		(cd $$dir && go clean) || exit 1; \
	done

.PHONY: android-build android-test android-lint android-coverage android-dep-check android-all
.PHONY: go-build go-test go-fmt go-vet go-mod-tidy-check go-test-race go-coverage-check go-vuln go-ci go-ci-component go-build-component go-test-component go-quality-component go-all
.PHONY: docker-up docker-down docker-build
.PHONY: api-smoke
.PHONY: ci-perms-check
.PHONY: all test clean help
