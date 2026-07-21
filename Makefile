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
	@echo "  test                   运行 Android 和 Go 测试"
	@echo "  clean                  清理构建产物"

# ---------------------------------------------------------------------------
# Android
# ---------------------------------------------------------------------------

# Cross-platform Gradle wrapper: use .bat on Windows, shell script elsewhere
# Windows 下通过 cd 进入 android 目录后执行 gradlew.bat，避免 -p 参数在 MinGW Make + PowerShell 环境下被误解析
ifeq ($(OS),Windows_NT)
GRADLEW := gradlew.bat
ANDROID_DIR := android
else
GRADLEW := ./gradlew
ANDROID_DIR := android
endif

# 移除 --no-daemon，启用 Daemon 大幅提升增量构建速度
# 添加 --build-cache 和 --parallel 确保缓存和并行编译生效
GRADLE_FLAGS := --stacktrace --build-cache --parallel

android-build:
	cd $(ANDROID_DIR) && $(GRADLEW) assembleDebug $(GRADLE_FLAGS)

android-test:
	cd $(ANDROID_DIR) && $(GRADLEW) :app:testDebugUnitTest $(GRADLE_FLAGS)

android-lint:
	cd $(ANDROID_DIR) && $(GRADLEW) lintDebug $(GRADLE_FLAGS)

android-coverage:
	cd $(ANDROID_DIR) && $(GRADLEW) jacocoTestReport $(GRADLE_FLAGS)

android-dep-check:
	cd $(ANDROID_DIR) && $(GRADLEW) dependencyCheckAnalyze $(GRADLE_FLAGS)

# ---------------------------------------------------------------------------
# Go Server
# ---------------------------------------------------------------------------

GO_COMPONENTS := api socks5-proxy tunnel

go-build:
	@for component in $(GO_COMPONENTS); do \
		echo "Building server/$$component ..." && \
		cd server/$$component && go build -v ./... && cd ../.. || exit 1; \
	done

go-test:
	@for component in $(GO_COMPONENTS); do \
		echo "Testing server/$$component ..." && \
		cd server/$$component && go test -v -coverprofile=coverage.out ./... && cd ../.. || exit 1; \
	done

go-vuln:
	@for component in $(GO_COMPONENTS); do \
		echo "Scanning server/$$component ..." && \
		cd server/$$component && govulncheck ./... && cd ../.. || exit 1; \
	done

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

android-all: android-build android-test android-lint android-coverage
	@echo "Android 完整检查完成"

go-all: go-build go-test go-vuln
	@echo "Go 完整检查完成"

all: android-all go-all

test: android-test go-test

clean:
	cd $(ANDROID_DIR) && $(GRADLEW) clean $(GRADLE_FLAGS)
	@for component in $(GO_COMPONENTS); do \
		cd server/$$component && go clean && cd ../.. || exit 1; \
	done

.PHONY: android-build android-test android-lint android-coverage android-dep-check android-all
.PHONY: go-build go-test go-vuln go-all
.PHONY: docker-up docker-down docker-build
.PHONY: all test clean
