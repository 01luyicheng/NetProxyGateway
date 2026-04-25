.PHONY: help
help:
	@echo "NetProxyGateway 构建工具"
	@echo ""
	@echo "Android 目标:"
	@echo "  android-build          构建 Android Debug APK"
	@echo "  android-test           运行 Android 单元测试"
	@echo "  android-lint           运行 Android Lint 检查"
	@echo "  android-coverage       生成 Android 测试覆盖率报告"
	@echo "  android-dep-check      运行 Android 依赖漏洞检查"
	@echo "  android-all            运行全部 Android 检查（构建+测试+lint+覆盖率）"
	@echo ""
	@echo "Go 服务端目标:"
	@echo "  go-build               构建全部 Go 服务"
	@echo "  go-test                测试全部 Go 服务"
	@echo "  go-vuln                运行 Go 漏洞扫描"
	@echo "  go-all                 运行全部 Go 检查（构建+测试+漏洞扫描）"
	@echo ""
	@echo "Docker 目标:"
	@echo "  docker-up              启动全部服务（docker-compose）"
	@echo "  docker-down            停止全部服务"
	@echo "  docker-build           构建全部 Docker 镜像"
	@echo ""
	@echo "通用目标:"
	@echo "  all                    运行 Android 和 Go 的完整构建与测试"
	@echo "  clean                  清理构建产物"

# ---------------------------------------------------------------------------
# Android
# ---------------------------------------------------------------------------

GRADLEW := android\gradlew.bat
GRADLE_FLAGS := --stacktrace --no-daemon

android-build:
	$(GRADLEW) -p android assembleDebug $(GRADLE_FLAGS)

android-test:
	$(GRADLEW) -p android :app:testDebugUnitTest $(GRADLE_FLAGS)

android-lint:
	$(GRADLEW) -p android lintDebug $(GRADLE_FLAGS)

android-coverage:
	$(GRADLEW) -p android jacocoTestReport $(GRADLE_FLAGS)

android-dep-check:
	$(GRADLEW) -p android dependencyCheckAnalyze $(GRADLE_FLAGS)

android-all: android-build android-test android-lint android-coverage

# ---------------------------------------------------------------------------
# Go Server
# ---------------------------------------------------------------------------

GO_COMPONENTS := api socks5-proxy tunnel

go-build:
	@for %%c in ($(GO_COMPONENTS)) do ( \
		echo Building server/%%c ... && \
		cd server/%%c && go build -v ./... && cd ../.. \
	)

go-test:
	@for %%c in ($(GO_COMPONENTS)) do ( \
		echo Testing server/%%c ... && \
		cd server/%%c && go test -v -coverprofile=coverage.out ./... && cd ../.. \
	)

go-vuln:
	@for %%c in ($(GO_COMPONENTS)) do ( \
		echo Scanning server/%%c ... && \
		cd server/%%c && govulncheck ./... && cd ../.. \
	)

go-all: go-build go-test go-vuln

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

all: android-all go-all

clean:
	$(GRADLEW) -p android clean $(GRADLE_FLAGS)
	@for %%c in ($(GO_COMPONENTS)) do ( \
		cd server/%%c && go clean && cd ../.. \
	)
