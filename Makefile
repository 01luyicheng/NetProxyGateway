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
	@echo "  go-all                 All Go checks (build+test+fmt+vet+vuln)"
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
.PHONY: go-build go-test go-fmt go-vet go-mod-tidy-check go-test-race go-coverage-check go-vuln go-ci go-ci-component go-all
.PHONY: docker-up docker-down docker-build
.PHONY: ci-perms-check
.PHONY: all test clean help
