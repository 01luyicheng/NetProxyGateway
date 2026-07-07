# CI 重构计划（修订版 v2）

> **目的**: 在保证质量门禁不降低的前提下，修复当前 GitHub Actions CI 的实际 bug、暴露的阻塞问题，并补齐自托管 runner 长期方案下的可观测性、安全与容量配置。
>
> **Runner 现状（长期）**: 项目使用自托管 runner `ar-npg-sfo3`（机器名 `ailoha-runner-sfo3`，注册标签 `[self-hosted, Linux, X64, do-sfo3]`）。**这是长期方案**，不是临时兼容。任何 CI 修改不应假设未来切回 `ubuntu-latest`，且不应在 workflow 文件中保留"切回 ubuntu-latest 时删除"形式的 TODO 注释——这类临时性 TODO 会被立即清理或纳入 `docs/RUNNER_DECISION.md` 单独追踪。Runner 自身的迁移决策由 `docs/RUNNER_DECISION.md` 维护（已包含在实施后清单中），不在本计划范围内。
>
> **原则**: 质量优先于成本。任何不降低质量的优化才采纳；门禁类步骤保持 `continue-on-error: false`。
>
> **文档性质**: 临时性实施计划。实施完成后应删除本文件；关键决策补录到 `docs/DECISIONS.md`，已确认 bug 登记到 `docs/ISSUES.md`，runner 长期配置策略补录到 `docs/RUNNER_DECISION.md`（**新增**，与本计划同步建立）。
>
> **相关文档**（本计划实施时同步建立）：
> - [PR #65 (`fix/ci-android-deadlock`)](https://github.com/01luyicheng/NetProxyGateway/pull/65) — 已完成 Step 1 中 timeout、artifact 路径、runner 标签大小写、fail-fast、T6 死锁 5 项修复
> - `docs/ISSUES.md` — N87/N88（本计划 Step 0 待登记）
> - `docs/RUNNER_DECISION.md` — runner 选型与长期配置（**本计划实施时同步建立**）

---

## 背景

当前仓库仅有一个 workflow 文件 `.github/workflows/ci.yml`，PR 启动 4 个 runner（1 Android + 3 Go matrix）。CI 当前 **0% 通过率**，根因有四层：

1. **PR #65 修复已存在但未合并**：[PR #65 `fix/ci-android-deadlock`](https://github.com/01luyicheng/NetProxyGateway/pull/65) 已完成计划 Step 1 中 5 项修复（artifact 路径、timeout、fail-fast、runner 标签大小写、T6 死锁），CodeRabbit 审查通过"无 actionable comments"，但因 N87（见下）阻塞无法合入。Step 1 中 cache 显式隔离（1.1）、Gradle 缓存清理（1.2）、`cancel-in-progress`（1.3）、最小权限（1.4）、CGo 配置（1.5）、ANDROID_HOME 移至 runner 级（1.6）**未在 PR #65 范围内**。
2. **N87：Android `testDebugUnitTest` 静默挂死**（run 28787711600 实测）。"Run unit tests" 步骤在 11:20:33 启动后**无任何 `=== RUN`/`PASS`/`FAIL` 输出**，持续 29 分 24 秒后被手动取消。该缺陷未在 T6 修复范围内，是 PR #65 合并后**唯一阻塞 dev 上 Android CI 100% 通过**的因素。
3. **N88：`server/api/main.go` CRLF 伪 diff**：`.gitattributes` 声明 `*.go text eol=lf`，但 git blob 实际为 CRLF，每次 checkout 产生伪 diff。需要在 Step 0 同步修正 `.gitattributes` 并执行 `git add --renormalize`。
4. **CGo/SQLite 测试失败**：`server/api/go.mod` 依赖 `github.com/mattn/go-sqlite3 v1.14.37`（CGo），`setup-go@v5` 默认 `CGO_ENABLED=0`，导致 `go test` 在 `server/api` 下 1 秒内 fail（run 28787711600 实测）。PR #65 body 已记录但未修复。

**当前 `ci.yml` 已确认问题**（与 PR #65 修复后对比）：

| # | 问题 | 位置 | 状态 |
|---|---|---|---|
| 1 | Android artifact 路径仍写 `android/app/build/...`，但 `android/build.gradle.kts` L12 已改 `buildDirectory` 为 `b/`，导致 debug APK、test results、JaCoCo coverage、lint、dependency-check 报告上传失败 | `ci.yml` L61/83-84/91/99-100/107 | PR #65 已修 |
| 2 | runner 标签 `[self-hosted, linux, x64, do-sfo3]`（小写），与 GitHub 注册的 `Linux`/`X64` 不匹配，job 0 秒失败 | `ci.yml` L23/118 | PR #65 已修 |
| 3 | 无 `timeout-minutes`，Android job 死锁时无限占用 runner | `ci.yml` 整体 | PR #65 已修 |
| 4 | Go matrix 未配置 `fail-fast: false`（实际还错误地放在 `matrix:` 内层，是 YAML 语法错误） | `ci.yml` L119-125 | PR #65 已修 |
| 5 | T6 测试死锁：`Socks5ConnectionPoolTest` 用 `Thread.yield()` + 2s 轮询，CI 高负载下挂死 | `Socks5ConnectionPoolTest.kt` | PR #65 已修 |
| 6 | Go cache 失败的根因：`server/shared/{httpclient,ratelimit,recovery,stringutil}` 4 个模块**无 `go.sum` 文件**（已通过 `Glob` 验证），`hashFiles` 找不到依赖文件导致 cache 永远 miss；表达式 `${{ matrix.component }}` 在单引号内不展开是次要问题 | `ci.yml` L132-138 + `server/shared/*` | Step 2 修 |
| 7 | Gradle `actions/cache@v4` 与 `gradle/actions/setup-gradle@v4` 内置缓存并存，存在冗余 | `ci.yml` L40-48 | Step 1 评估 |
| 8 | `server/shared/*` 4 个模块无**独立** CI job（但已通过 `replace` 指令被 api/socks5-proxy/tunnel 间接测试）；是否加入 matrix 需权衡：单 runner 下每多 1 个 matrix job 约增加 2-5 分钟排队 | CI matrix `component` | Step 2-3 评估 |
| 9 | commitlint 嵌在 Android job 内：Android build/test 失败时 commitlint 不执行，无法独立验证 commit 规范 | `ci.yml` L109-114 | Step 3 修 |
| 10 | 缺少关键质量检查：无 `gofmt`、`go vet`、`go mod tidy` 校验、`go test -race`、Android lint SARIF 上传、`dependency-review-action` | 整体 | Step 4 修 |
| 11 | 核心业务逻辑测试覆盖不足（ISSUES.md N8）：`processVpnTraffic`、`startHeartbeat` 覆盖薄弱；`forwardViaSocks5` 已补部分用例 | 测试目录 | Step 5 修 |
| 12 | AGENTS.md 第 5 节仍将 H5 列为"关键缺陷（需立即修复）"，但 ISSUES.md 已标 H5 为"已修复"（提交 `b1e18bd`），需同步修正 | `AGENTS.md` 第 5 节 | Step 7 修 |
| 13 | 持久 self-hosted runner 缺少并发隔离（`$HOME/.cache/go-build` 跨 job 共享会污染）、`cancel-in-progress: false` 导致排队、无主动磁盘清理、无安全威胁模型 | runner 配置 + ci.yml L11-13 | Step 1 + Runner 配置章节修 |
| 14 | `CGO_ENABLED=0` 默认值导致 `server/api` 全部测试 fail | setup-go 默认 | Step 1 修 |
| 15 | `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: 'true'` 环境变量：Node 20 已弃用，当前靠强制使用 Node 24 过渡。需确认各 action 对 Node 24 的兼容性，长期应移除该变量依赖 | ci.yml L16 | Step 7 后评估 |

---

## 步骤依赖关系

```
Step 0（合并 PR #65 + 修 N87 + 修 N88）
    ↓
Step 1（补齐持久 self-hosted CI 配置）
    ↓
Step 2（补齐 shared 模块 + Makefile 目标 + CGo）
    ↓
Step 3（拆分 workflow + 路径过滤 + runner-cleanup）
    ↓
Step 4（新增非破坏性质量检查）
    ↓
Step 5（测试补齐）
    ↓
Step 6（race detector + diff coverage）
    ↓
Step 7（统一 Makefile 调用 + 分支保护 + self-hosted 安全基线）
```

**关键前置条件**：
- **所有步骤依赖 Step 0**：N87 修好前分支保护一旦启用 `android-ci`，所有 PR 永久阻塞。
- Step 1 依赖 Step 0：cache 隔离策略需在 PR #65 的 cache 修复基础上扩展。
- Step 3 依赖 Step 2：新 workflow 调用第 2 步创建的 Makefile 目标。
- Step 4 依赖 Step 3：质量检查在拆分后的 workflow 中配置。
- Step 6 依赖 Step 3：覆盖率检查目标需要 Makefile 和 Gradle 任务支持。
- Step 7 依赖 Step 1-4：分支保护需要核心 workflow 先在 dev 上验证通过。Step 5、6 作为质量提升，不强制成为 required check。

---

## 实施步骤

### Step 0：合并 PR #65 + 修复 N87 / N88（最高优先级，本步骤为强阻塞门禁）

**目标**：让现有 CI 真正转绿。所有后续步骤必须在本步骤完成后才能开始。

#### 0.1 合并 PR #65 到 dev

1. 检查 PR #65 当前是否已 rebase 到最新 dev：
   ```bash
   gh pr view 65 --json headRefName,mergeable,statusCheckRollup
   ```
2. 若 `mergeable: CONFLICTING`，协助解决冲突（冲突应仅出现在 `ci.yml` 的 runner 标签和 timeout 段）。
3. 合并方式：建议 **squash merge**（保留单一 commit 哈希便于追溯）；合并后立即验证 `make android-test` 和 `make go-test` 本地通过。
4. PR #65 body 中提到的"`server/api/main.go` CRLF 伪 diff"和"`RootDetectorTest.checkBusyBox` 既存失败"两个未登记问题，在合并后**必须**登记到 `docs/ISSUES.md`（编号 N87、N88），保持文档与代码同步。

#### 0.2 修复 N87：Android `testDebugUnitTest` 静默挂死

**目标**：定位 30 分钟静默挂死的根因并修复。修复前不得在 dev 启用分支保护。

定位策略（按顺序尝试）：

1. **分段定位**：在 self-hosted runner 上执行
   ```bash
   ./android/gradlew -p android :app:testDebugUnitTest --tests "com.netproxy.gateway.vpn.*" --info --stacktrace --no-daemon 2>&1 | tee /tmp/n63-vpn.log
   ```
   对每个子包重复执行（`vpn.*` / `proxy.*` / `connection.*` / `security.*` / `ui.*`），找出最早进入静默的子包。
2. **JVM 配置**：尝试降低 `-Xmx` 和加大 Metaspace，看是否 OOM 后 JVM 静默退出码。检查 `gradle.properties` 中 `org.gradle.jvmargs` 是否合理。
3. **Robolectric 资源初始化**：N7 之后测试量大幅增长，Robolectric `@Config(sdk=...)` 与 `testOptions.unitTests.isIncludeAndroidResources = true` 组合下可能存在并发初始化竞争。验证 `maxParallelForks`：
   ```kotlin
   // android/app/build.gradle.kts
   android {
     testOptions {
       unitTests.all {
         it.maxParallelForks = 1   // 临时串行化，验证是否消除挂死
       }
     }
   }
   ```
4. **若仍无法定位**：临时将该步骤降级为 `continue-on-error: true`，先让 CI 跑通；待定位到具体测试类后再恢复硬门禁。**降级方案必须在 `docs/ISSUES.md` 显式标注**，不允许悄悄软化。

修复后验证：
- `make android-test` 在 self-hosted runner 上完整跑通
- 单次完整 run（包含 5 处 upload-artifact 路径已修正）成功

#### 0.3 修复 N88：`server/api/main.go` CRLF

**前置验证**（先确认问题是否真的存在）：
```bash
git ls-files --eol server/api/main.go
# 若 i/ 列显示 crlf 而 w/ 显示 lf，说明 git blob 中是 CRLF 但工作目录是 LF
# 若都是 lf，说明问题不存在，跳过本小节
```

如果确认存在 CRLF 伪 diff 问题：

1. 检查仓库根 `/.gitattributes`（当前已有 `*.go text eol=lf`，无需新增）
2. 执行 renormalize：
   ```bash
   git add --renormalize .
   git diff --stat   # 应看到 server/api/main.go 等的 EOL 变更
   git commit -m "fix: renormalize line endings (N88)"
   ```
3. 通知所有正在工作的分支 rebase 一次以应用新 `.gitattributes`。

#### 0.4 验证标准（Step 0 完成门禁）

- [ ] PR #65 已合并到 dev
- [ ] `docs/ISSUES.md` 已登记 N87（含修复策略或降级方案）、N88（含 renormalize commit 或"已验证不存在"结论）
- [ ] Android CI 至少 **1 次完整 run 100% 通过**（含 lint/coverage/dependency-check artifact 上传）。单 runner 场景下要求 3 次会阻塞后续工作太久，放宽为 1 次完整通过 + 关键模块各跑 1 次
- [ ] Go CI 三组件均通过
- [ ] `make android-test` 在本地通过

**未完成 Step 0 之前，禁止进入 Step 1。**

---

### Step 1：补齐持久 self-hosted CI 配置（PR #65 未覆盖的部分）

**目标**：在 PR #65 修复基础上，补齐持久自托管 runner 场景特有的配置（cache 隔离、并发取消、env 固化）。

PR #65 已完成：artifact 路径、timeout、fail-fast、runner 标签大小写、T6 死锁修复。本步骤不再重复。

#### 1.1 Go cache 显式隔离（持久 runner 必需）

**当前状态**：PR #65 沿用原 `actions/cache@v4` + symlink 方案；但 `setup-go@v5` 默认 `cache: true` 也会尝试做缓存，两者**可能冲突**（setup-go 用默认 `GOMODCACHE`/`GOCACHE` 路径，actions/cache 用自定义路径）。从 run 28795161026 日志可见，setup-go 内置缓存因找不到 `go.sum` 而失败，与 actions/cache 形成双重冗余。

**新方案（统一单一缓存层，避免冲突）**：
- 关闭 `setup-go@v5` 内置缓存：`cache: false`
- 在 `go-build` / `go-ci` job 顶部硬编码 env 块（**非** TODO，因为 self-hosted 长期）：
  ```yaml
  env:
    GOMODCACHE: ${{ runner.temp }}/go-mod-${{ matrix.component }}
    GOCACHE: ${{ runner.temp }}/go-build-${{ matrix.component }}
  ```
- 删除"Link GOPATH mod cache"步骤（因为 GOMODCACHE 已通过 env 直接指向 runner.temp 下的目录）
- 保留 `actions/cache@v4`，**同时缓存 GOMODCACHE 和 GOCACHE 两个目录**，key 中用 `format()` 函数让 `${{ matrix.component }}` 在表达式内被求值：
  ```yaml
  - uses: actions/cache@v4
    with:
      path: |
        ${{ runner.temp }}/go-mod-${{ matrix.component }}
        ${{ runner.temp }}/go-build-${{ matrix.component }}
      key: ${{ runner.os }}-go-${{ matrix.component }}-${{ hashFiles(format('{0}/go.sum', matrix.path), format('{0}/go.mod', matrix.path)) }}
      restore-keys: |
        ${{ runner.os }}-go-${{ matrix.component }}-
  ```
  （其中 `matrix.path` 来自 Step 3.3 的 `matrix.include` 定义）
- 在文件顶部加注释说明此 env 块是"持久 self-hosted 专用，长期保留"。

**为什么不用 setup-go 内置缓存**：持久 runner 上默认 `GOMODCACHE=~/go/pkg/mod` 是跨 job 共享的，并发 job 会产生竞态。必须把缓存目录隔离到 `runner.temp` 下按 component 分目录，这超出了 setup-go 内置缓存的配置能力。

#### 1.2 Gradle 缓存：删除冗余 `actions/cache@v4`

`gradle/actions/setup-gradle@v4` 已内置缓存。删除 `ci.yml` L40-48 的 `actions/cache@v4` 步骤。

观察一周：若 cache 命中率或构建速度明显下降（对比 PR #65 合并前），再加回针对 Android 子项目的 `actions/cache@v4`。

#### 1.3 启用 `concurrency` 取消策略

当前 `concurrency: cancel-in-progress: false` 在单台持久机器上必然导致 PR 推送后排队。

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true   # 持久 self-hosted 必需
```

**注意事项**：
- 对 PR 而言：同一 PR 推送新 commit 时取消旧 run，合理
- 对 `push` 到 `main`/`dev` 而言：连续推送两个 commit 会取消前一个。这在单 runner 场景下可以接受（节省时间），但如果需要保证每个 commit 都有完整 CI 记录，可考虑改用 `workflow_run` 或为 push 使用不同的 concurrency group
- 取消时旧 run 显示 cancelled（非红色 failure），不会影响分支保护判断

风险：取消时正在进行的 artifact 上传可能中断。在 `docs/RUNNER_DECISION.md` 记录此行为供 reviewer 知晓。

#### 1.4 显式最小权限

给 workflow 添加 `permissions: contents: read`（后续 upload-artifact 步骤在 job 级别提升为 `actions: write`）。

#### 1.5 修正 setup-go 配置

- `go-version: '1.25'` 改为 `go-version-file: server/api/go.mod`（api 需 1.25.0 最高，向下兼容 socks5-proxy/tunnel/shared 的 1.22）。Step 3 拆分后各 component 用自己的 `go.mod` 作为 version file。
- 显式设置 `cache: false`（与 Step 1.1 统一，避免与自定义 GOMODCACHE/GOCACHE 冲突）
- 增加 `env: CGO_ENABLED: '1'` 修复 N-CGo（`server/api` 的 sqlite3 测试）

**同时**在 self-hosted runner 上确认 `gcc --version` 可用（CGo 依赖）；若不可用，先安装 gcc 或记录到 runner 配置。

#### 1.6 ANDROID_HOME 移至 runner 级

不再在 workflow 中设置 `ANDROID_HOME: /opt/android-sdk`、`ANDROID_SDK_ROOT: /opt/android-sdk`——而是在 self-hosted runner 启动时（`/etc/environment` 或 runsvc 启动脚本）永久固化。

workflow 起步增加 SDK 存在性校验：
```yaml
- name: Validate Android SDK
  run: |
    if [ ! -d "$ANDROID_HOME/platforms" ]; then
      echo "::error::Android SDK missing at $ANDROID_HOME"
      exit 1
    fi
```

**门禁清单**（必须 `continue-on-error: false`）：build、test、artifact upload 步骤。lint 与 dependency-check 初期可保持 `continue-on-error: true`，待存量问题修复后再收紧。

**验证标准**：
- Go matrix 三组件构建通过；cache 命中且无污染
- `gcc --version` 在 runner 上可用
- 并发 PR 推送时旧 run 被正确取消

---

### Step 2：补齐 shared 模块和 Makefile 目标

**目标**：为后续步骤提供可调用项，并统一本地与 CI 行为。

1. 对 `server/shared/{httpclient,ratelimit,recovery,stringutil}` 分别执行 `go mod tidy`，生成 `go.sum` 并提交。
   - **注意**：当前 4 个 shared 模块的 `go.mod` 多数为空（`require ()` 块为空），`go mod tidy` 可能不生成 `go.sum`。需逐个验证：
     - 若 tidy 后有依赖 → 正常生成 go.sum
     - 若 tidy 后无变化 → 手动 `touch go.sum` 提交空文件（**仅一次**，让 cache key 生效）
2. shared 模块的引用方（`server/api`、`server/socks5-proxy`、`server/tunnel`）在引用它们的目录下再执行一次 `go mod tidy`，确保 replace 关系一致。
3. 更新 `Makefile`：
   - 拆分组件集合。**注意 shared 模块物理路径在 `server/shared/*` 而非 `server/*`，需要单独定义路径变量**：
     ```makefile
     GO_SERVER_COMPONENTS := api socks5-proxy tunnel
     GO_SHARED_COMPONENTS := httpclient ratelimit recovery stringutil

     # 用于路径查找：shared 模块在 server/shared/ 子目录
     GO_COMPONENT_PATH = $(if $(filter $(GO_SHARED_COMPONENTS),$(COMPONENT)),server/shared/$(COMPONENT),server/$(COMPONENT))
     GO_COMPONENTS := $(GO_SERVER_COMPONENTS) $(GO_SHARED_COMPONENTS)
     ```
   - 新增 CI 专用目标 `go-ci`：build + test + fmt + vet + mod-tidy-check，覆盖 `GO_COMPONENTS`。
   - 新增 CI matrix 专用目标 `go-ci-component`：接收 `COMPONENT` 变量，使用 `$(GO_COMPONENT_PATH)`（Step 2 第 3 项定义的路径映射）执行 build + test + fmt + vet + mod-tidy-check。
   - 新增本地目标：`go-fmt`、`go-vet`、`go-mod-tidy-check`、`go-test-race`、`go-coverage-check`。
   - `go-all` 保留为本地"全量"目标（可继续包含 `go-vuln`）。
   - **修改 `android-all` 目标**：分三步，test 失败必须立即停，lint/coverage/dep-check 作为第二步（`--continue` 只用于非关键步骤）：
     ```bash
     # 第一步：test 必须通过（set -e 行为）
     ./gradlew testDebugUnitTest
     # 第二步：test 通过后再跑 coverage + lint + dep-check（允许 lint/dep-check 失败但不掩盖 test 失败）
     ./gradlew jacocoTestReport lintDebug dependencyCheckAnalyze --continue
     ```
     Makefile 注释中需显式声明："`--continue` 仅用于第二步，**不能**用于 test 步骤。test 失败必须立即停。"
     （注：当前 `android-all` 不含 `android-dep-check`，本次重构同步补上，与 CI 行为对齐。）
   - 增加 `CI` 环境检测：CI 下 `GRADLE_FLAGS` 追加 `--no-daemon`。
4. **shared 模块的 CGo 处理**：若 `server/shared/*` 中需要 CGo（如未来添加），在 Makefile 目标中同样设置 `CGO_ENABLED=1`。

**验证标准**：
- shared 模块均有 `go.sum`（即使是空文件）
- 新增 Makefile 目标本地可调用
- `make go-ci` 覆盖 shared 模块
- `make android-all` 完成 build/test/coverage/lint

**注意**：`go-mod-tidy-check` 应通过 `go mod tidy` 后检查 git diff 是否干净来验证（不是修改文件）。Makefile 注释中需说明本地运行前应先提交或暂存变更。

---

### Step 3：拆分 workflow 并加路径过滤

**前置条件**：Step 1、Step 2 已完成。

**目标**：减少无关 runner 启动，同时不阻塞 required check。

#### 3.1 路径过滤策略

> ⚠️ **关键约束**：设为 **required check** 的 workflow **绝对不能**用 workflow 级 `on.pull_request.paths` 过滤。如果 PR 只改了文档，workflow 根本不触发，required check 永远处于 pending 状态，PR 无法合并。

采用以下策略：

1. **required check 的 workflow 用 `paths-ignore` 而非 `paths`**：
   - 纯文档/配置变更 PR 仍会触发 workflow，但 job 内部用 `dorny/paths-filter@v3` 判断无相关变更后，**快速跳过核心步骤并输出 success 结论**（让 check 变绿）。
   - `on.push` 到 main/dev 仍不限路径（保证任意 commit 触发全量冒烟）。
2. **非 required check 的 workflow（security、secret-scan、runner-cleanup）**：可用 `paths` 或定时触发，不受上述约束。
3. **Go matrix 细粒度过滤**：job 内用 `dorny/paths-filter@v3` 按 component 路径独立判断，无关 component 跳过 build/test 但仍输出 success（不影响 required check 状态）。

**关键实现要求**：
- 每个 workflow 只暴露 **一个 job name**（顶层 `name:` 不设，依靠 job 内部 name 控制 check name）
- 现有 check name 保持不变：`Android Build & Test`、`Go Server Build (api)`、`Go Server Build (socks5-proxy)`、`Go Server Build (tunnel)`
- 新增 shared 模块 check name：`Go Server Build (httpclient)` 等
- 分支保护 required check 按 check name 匹配
- **路径过滤跳过的 component 必须输出 success 结论**（不能是 skipped/cancelled 状态）

**paths-filter 确保 success 的实现方式**：
> ⚠️ 用 `if:` 跳过步骤不会改变 job 结论，但如果所有步骤都被跳过，job 结论可能是 `skipped` 而非 `success`。required check 要求的是 `success` 结论。

正确实现方式——确保 job 至少有一个步骤始终执行：
```yaml
- name: Detect changes
  id: filter
  uses: dorny/paths-filter@v3
  with:
    filters: |
      android:
        - 'android/**'
        - 'Makefile'
        - '.github/workflows/android-ci.yml'

- name: Build & Test
  if: steps.filter.outputs.android == 'true'
  run: make android-all

- name: No changes (skip)
  if: steps.filter.outputs.android == 'false'
  run: echo "No Android-related changes, skipping build & test"
```
这样无论是否有变更，job 都会执行到最后并输出 `success` 结论。

对于 Go matrix 的细粒度过滤：每个 matrix job 内用同样的模式——paths-filter 判断当前 component 路径是否有变更，无变更时输出 skip 消息但 job 仍为 success。

#### 3.2 新增 `.github/workflows/runner-cleanup.yml`

持久 self-hosted runner 不会自动清盘。`android/b/` 每次 Android job 产生 2-5 GB，加上 Go 构建缓存和工作目录残留，单 runner 每天多个 PR 后很快占满磁盘（典型 80-160 GB VPS）。`${{ runner.temp }}` 在 self-hosted 上**不是 ephemeral**。

**两层清理策略**：

1. **每次 job 后轻量清理**（在各 workflow 的最后一步执行，用 `if: always()` 确保即使失败也清理）：
   - Android job：`rm -rf android/b/`（构建产物，artifact 已上传，无需保留）
   - Go job：**仅清理 `$GOCACHE`**（编译产物，体积大且 component 间不可共享）；**保留 `$GOMODCACHE`**（模块缓存，`actions/cache` 下次 run 可直接从本地磁盘命中，远快于从 GitHub cache 存储重新下载）
     ```bash
     # Go per-job cleanup — 只清 build cache，不动 mod cache
     rm -rf "$GOCACHE"
     ```
2. **每日深度清理**（`runner-cleanup.yml`）：清理过期 `$GOMODCACHE`、Gradle 缓存、孤立工作目录。

```yaml
name: Runner Cleanup
on:
  schedule:
    - cron: '0 4 * * *'   # 每天 04:00（低峰期）
  workflow_dispatch:

jobs:
  cleanup:
    runs-on: [self-hosted, Linux, X64, do-sfo3]
    timeout-minutes: 30
    steps:
      - name: Disk usage before
        run: df -h
      - name: Clean stale runner temp directories
        run: |
          # 清理 runner.temp 中超过 3 天的 go-mod-* / go-build-* / gradle-* 目录
          find "$RUNNER_TEMP" -maxdepth 1 -type d -mtime +3 \
            \( -name 'go-mod-*' -o -name 'go-build-*' -o -name 'gradle-*' \) \
            -print -exec rm -rf {} + 2>/dev/null || true
      - name: Clean Gradle build cache
        run: |
          rm -rf ~/.gradle/caches/build-cache-*
          rm -rf ~/.gradle/caches/transforms-*
      - name: Clean orphaned workspace directories
        run: |
          # runner 工作目录下未被当前 run 使用的旧 checkout
          WORK_ROOT="${GITHUB_WORKSPACE%/*/*}"
          if [ -d "$WORK_ROOT" ]; then
            find "$WORK_ROOT" -maxdepth 2 -type d -name 'NetProxyGateway' -mtime +7 \
              -print -exec rm -rf {} + 2>/dev/null || true
          fi
      - name: Disk usage after
        run: df -h
```

> ⚠️ **注意**：所有 `find ... -exec rm -rf` 前都先 `-print` 输出待删列表，便于审计。首次执行前建议手动 dry-run 验证。

#### 3.3 新增文件

- **`.github/workflows/pr-checks.yml`**
  - 触发：所有 PR（path filter：`['.github/workflows/**', '**/*']`，确保 commitlint + dependency-review 始终跑）
  - 包含两个轻量 job：
    - `commitlint`：`actions/checkout@v5`（`fetch-depth: 0`） + `wagoid/commitlint-github-action@v6`
    - `dependency-review`：`actions/dependency-review-action@v4`，配置 `fail-on-severity: high`
  - 顶层 name 不设（job 内部 name 决定 check name：`commitlint`、`dependency-review`）
  - 权限：`contents: read`, `pull-requests: write`（dependency-review 需要）

- **`.github/workflows/android-ci.yml`**
  - 触发：`push` 到 main/dev（不限路径）+ 所有 PR（用 `paths-ignore` 排除纯文档，而非 `paths` 包含——确保 required check 始终产生结论）
  - 单 job `Android Build & Test`（**保持原 check name，required check**）：
    - job 内用 `dorny/paths-filter@v3` 检查 `android/**`、`Makefile`、`.github/workflows/android-ci.yml`
    - 无相关变更时：跳过 build/test，直接 echo "No Android changes, skipping" 并 exit 0（输出 success 结论）
    - 有相关变更时：`actions/setup-java@v4` + `gradle/actions/setup-gradle@v4` + `make android-all`
  - artifact 路径使用 `android/b/app/...`（PR #65 修复后）
  - 权限：`contents: read`、`actions: write`（上传 APK/test/coverage/lint artifact）
  - 顶部增加 Step 1.6 的 SDK 存在性校验
  - 关键变化：**不**再包含 commitlint（已迁移到 pr-checks.yml）

- **`.github/workflows/go-ci.yml`**
  - 触发：`push` 到 main/dev（不限路径）+ 所有 PR（用 `paths-ignore` 排除纯文档，确保 required check 始终产生结论）
  - 单 job `Go Server Build`（**required check**）：
    - job 内用 `dorny/paths-filter@v3` 按 matrix component 路径独立判断
    - 无相关变更的 component：跳过 build/test，输出 success（不影响 required check 状态）
    - 有相关变更的 component：`actions/setup-go@v5`（`go-version-file: ${{ matrix.path }}/go.mod`，从 matrix.include 中定义）+ `env: CGO_ENABLED: '1'` + `make go-ci-component COMPONENT=${{ matrix.component }}`
  - 矩阵（使用 `include` 确保 component 与 path 一一对应，**避免**两个独立数组产生笛卡尔积）：
    ```yaml
    strategy:
      fail-fast: false
      matrix:
        include:
          - component: api
            path: server/api
          - component: socks5-proxy
            path: server/socks5-proxy
          - component: tunnel
            path: server/tunnel
          - component: httpclient
            path: server/shared/httpclient
          - component: ratelimit
            path: server/shared/ratelimit
          - component: recovery
            path: server/shared/recovery
          - component: stringutil
            path: server/shared/stringutil
    ```
    **关键**：使用 `matrix.include` 定义一对一映射，**禁止**用两个独立数组（会产生 7×7=49 个 job 的笛卡尔积）

    > **ROI 权衡说明**：shared 模块（httpclient/ratelimit/recovery/stringutil）已通过 `replace` 指令被 api/socks5-proxy/tunnel 间接测试。将它们加入 matrix 的增量收益是"独立报 test 结果 + 独立 check name"，增量成本是每次 PR 多 4 个 job（单 runner 下约增加 8-20 分钟排队）。
    >
    > **降级方案**：若实施后单 PR CI 总时间超过 60 分钟（影响开发效率），可将 shared 4 个模块从 matrix 中移除，合并为单个 job `Go Shared Build` 串行执行：
    > ```yaml
    > - name: Build & test shared modules
    >   run: |
    >     for mod in httpclient ratelimit recovery stringutil; do
    >       cd server/shared/$mod && go build ./... && go test ./... && cd ../../..
    >     done
    > ```
    > 此方案将 7 个 Go job 降回 4 个（3 主组件 + 1 shared），且共享同一个 `$GOMODCACHE`（shared 无外部依赖，不会污染）。
  - 顶层 name 不设，job 内部 name 保持 `Go Server Build`，matrix value 拼成 `Go Server Build (api)` 等
  - 权限：`contents: read`、`actions: write`（上传 coverage artifact）
  - **env 块（持久 self-hosted 专用）**：
    ```yaml
    env:
      GOMODCACHE: ${{ runner.temp }}/go-mod-${{ matrix.component }}
      GOCACHE: ${{ runner.temp }}/go-build-${{ matrix.component }}
    ```
    文件顶部加注释："持久 self-hosted 专用 env，长期保留。Runner 迁移时按 docs/RUNNER_DECISION.md 决策。"

- **`.github/workflows/security.yml`**
  - 触发：`push` 到 main/dev 时依赖文件变化（`**/go.mod`、`**/go.sum`、`android/**/*.gradle*`、`android/**/libs.versions.toml`，paths filter）+ 所有 PR（同样按路径过滤）+ 每晚定时 `0 2 * * *`
  - 步骤：`actions/checkout@v5` + `actions/setup-java@v4` + `gradle/actions/setup-gradle@v4`（Android 侧）+ `actions/setup-go@v5`（Go 侧）+ Android `dependencyCheckAnalyze` + Go `govulncheck@latest`（漏洞扫描建议用最新版本，因为漏洞数据库在持续更新；与 7.3 分级策略不冲突，因为 `govulncheck` 是 Go 官方工具）
  - 顶层 name 不设，job name `Security Scan`
  - 权限：`contents: read`, `security-events: write`、`actions: write`
  - **不作为分支保护 required check**

- **`.github/workflows/secret-scan.yml`**（Step 4 实现，本步骤仅占位）
  - 触发：所有 PR + 每晚定时
  - 步骤：`gitleaks/gitleaks-action@v2` + SARIF 上传
  - 顶层 name 不设，job name `Secret Scan`
  - 权限：`contents: read`, `security-events: write`

删除原 `.github/workflows/ci.yml`。

#### 3.4 验证标准

- 纯 Android 变更 PR：`go-ci` workflow 仍触发，但 Go 各 component 因 paths-filter 判断无相关变更而快速跳过，**check 结论为 success**（非 skipped/cancelled），required check 变绿
- 纯 Go 变更 PR：`android-ci` 同上，快速跳过并输出 success
- 文档-only PR：`android-ci` / `go-ci` 均触发但快速跳过（success），`commitlint` + `dependency-review` 正常执行
- `security.yml` 在依赖变更 PR 上正确触发
- `runner-cleanup.yml` 每天执行后磁盘释放 ≥ 5 GB（视项目实际大小调整）
- **关键验证**：推送一个仅改 `docs/` 的 PR，确认 `Android Build & Test` 和 `Go Server Build (*)` 所有 check 均显示绿色 success 结论（不是 pending/skipped）

#### 3.5 旧 PR 迁移

在删除 `ci.yml` 前，处理所有 open PR：

1. `gh pr list --base dev --state open --json number,headRefName,files --jq '.[] | select(.files[].path | startswith(".github/")) | {number, headRefName}'` 列出修改 workflow 的 PR
2. 对每个受影响 PR 评论说明："`dev` 已迁移到新 workflow，请 rebase 到最新 dev 并解决冲突"
3. **重复 PR 处理**：用 `gh pr list` 按标题相似度识别疑似重复 PR（如多个 palette 相关 PR）。**需人工确认后再关闭**，不自动批量关闭。迭代演进的 PR 可能包含不同的改进点，误关会丢失上下文。
4. 设定迁移截止点：新 workflow 启用后，未 rebase 的旧 PR 不得直接合并

---

### Step 4：新增非破坏性质量检查

**目标**：补上低风险、高价值的检查。

#### 4.1 存量违规量评估（**强制前置**）

在启用任何 `gofmt`/`go vet`/`go mod tidy` 作为硬门禁前，**必须**单独提一个调研 PR：

```bash
# 在 dev 分支本地跑
make go-fmt        # 统计违规文件数
make go-vet        # 统计 warning 数
go mod tidy && git diff --stat  # 统计 tidy 变更文件数
```

将违规清单写到 issue / PR description，**用户确认违规量可接受后才能进入 4.2-4.3**。若违规量超过 50 个文件或 200 行，**分模块**（先 server/api、再 socks5-proxy、再 tunnel、最后 shared）顺序提交清理 PR。

#### 4.2 Go 质量检查

在 `go-ci.yml` 中调用 Step 2 定义的 Makefile 目标：
- `go-fmt`：`gofmt -l server/` 列出未格式化文件
- `go-vet`：`go vet ./...` 输出 warning
- `go-mod-tidy-check`：`go mod tidy && git diff --exit-code`
- **先以 `continue-on-error: true` 跑一周收集违规基线**，再收紧为 `continue-on-error: false`

#### 4.3 Android 质量检查

在 `android-ci.yml` 中（paths-filter 匹配后执行）：
- 上传 lint SARIF 到 GitHub Code Scanning：`github/codeql-action/upload-sarif@v3`
- 需要 `permissions: security-events: write`（job 级）
- SARIF 上传步骤可用 `continue-on-error: true`（上传失败不阻塞主流程）

#### 4.4 Secret 扫描

启用 `.github/workflows/secret-scan.yml`：
- `gitleaks/gitleaks-action@v2`
- SARIF 上传到 GitHub Code Scanning
- **先以 `continue-on-error: true` 跑一周**，处理完所有误报（`.gitleaksignore` 或规则排除）后再收紧
- **注意**：私有仓库/org 使用 gitleaks-action 可能需要 license，需先验证

#### 4.5 Docker 构建验证

项目有 3 个 Dockerfile（api/socks5-proxy/tunnel），但 CI 从未验证 Docker 镜像能否成功构建。PR #66（修复 api Dockerfile 的 CGO/SQLite 崩溃）说明 Dockerfile 会因代码变更默默坏掉。

在 `go-ci.yml` 中增加一个 matrix component `docker-build`（或在 `security.yml` 中增加步骤），验证 Docker 构建：

```yaml
- name: Verify Docker builds
  if: steps.filter.outputs.server == 'true'
  run: |
    for svc in api socks5-proxy tunnel; do
      echo "::group::Docker build $svc"
      docker build -t netproxy/$svc:ci-test -f server/$svc/Dockerfile server/$svc
      echo "::endgroup::"
    done
- name: Clean up Docker test images
  if: always()
  run: docker image prune -f --filter "label=netproxy-ci-test" 2>/dev/null || true
```

**注意事项**：
- 只验证构建成功，不 push 镜像
- `continue-on-error: true` 先跑一周确认基线，再收紧为硬门禁
- 需确认 self-hosted runner 上 Docker daemon 可用（`docker info`）
- 构建耗时约 2-5 分钟/服务，3 个服务串行约 6-15 分钟。若耗时过长，可只对 `server/**` 路径变更的 component 执行 Docker build

**门禁清单**（必须 `continue-on-error: false`）：
- `commitlint`（pr-checks.yml）
- `dependency-review`（pr-checks.yml）
- `gofmt`/`go vet`/`go-mod-tidy-check`（go-ci.yml，**4.1 评估通过后**）
- `gitleaks` 扫描步骤本身（secret-scan.yml，**4.1 误报处理后**）
- Docker 构建验证（go-ci.yml 或 security.yml，**4.5 基线通过后**）

**验证标准**：
- `gofmt`、`go vet`、`go mod tidy` 在 dev 上违规量为 0 后启用硬门禁
- SARIF 在 GitHub Security tab 可见
- `dependency-review-action` 在 PR 引入 high/critical 依赖漏洞时阻塞合并
- 3 个 Dockerfile 均能成功构建（`docker build` 返回 0），且 runner 上 Docker daemon 可用

---

### Step 5：核心业务逻辑测试补齐

**目标**：处理 ISSUES.md N8 测试缺口，为 race detector 和覆盖率阈值提供基础。

N8 列出的 3 个核心函数均位于现有源文件，测试文件已存在。本步骤为**向现有测试文件追加用例**：

1. `processVpnTraffic`（`vpn/VpnService.kt`，`private suspend fun`）
   - 测试文件：`android/app/src/test/java/com/netproxy/gateway/vpn/VpnServiceTest.kt`（已存在，追加用例）
   - **可见性处理**：将 `private` 改为 `internal`（Kotlin 模块内可见），并用 `@VisibleForTesting` 标注。**注意**：`@VisibleForTesting` 仅是 lint 提示，**不修改字节码可见性**——必须实际修改 `private` → `internal`。
   - **防误用措施**：在方法签名前添加 KDoc 警告注释，防止其他开发者"顺便"调用测试专用方法：
     ```kotlin
     /**
      * WARNING: Exposed as `internal` solely for unit testing.
      * Do NOT call from production code — use the public API instead.
      */
     @VisibleForTesting
     internal suspend fun processVpnTraffic(...) { ... }
     ```
     可选强化：使用 `@RestrictTo(RestrictTo.Scope.TESTS)`（来自 `androidx.annotation`），IDE 会在非测试代码调用时显示编译警告。
2. `forwardViaSocks5`（`vpn/ConnectionSessionManager.kt`，`fun`）
   - 测试文件：`android/app/src/test/java/com/netproxy/gateway/vpn/ConnectionSessionManagerTest.kt`（已存在，追加用例）
   - 当前已有 4 个基础用例；本步骤重点补充边界/异常路径
3. `startHeartbeat`（`connection/MqttConnectionManager.kt`，`private fun`）
   - 测试文件：`android/app/src/test/java/com/netproxy/gateway/connection/MqttConnectionManagerHeartbeatTest.kt`（已存在，追加用例）
   - **可见性处理**：同 `processVpnTraffic`（`private` → `internal` + KDoc 警告注释 + `@VisibleForTesting`，可选 `@RestrictTo`）

**测试框架**：MockK + Robolectric（项目已使用）。

**验证标准**：
- `processVpnTraffic` 和 `startHeartbeat` 各至少 3 个测试用例（正常/边界/异常）
- `forwardViaSocks5` 补充到至少 6 个用例（含异常）
- `make android-test` 通过

**说明**：`processReturnTraffic`（关联 TECH_DEBT.md C80）作为后续可选测试项，本步骤不强制要求。

---

### Step 6：引入 race detector 和 **diff coverage** 阈值

**目标**：建立**增量覆盖率**约束（避免旧代码低覆盖导致新代码被卡死）；对 Go 代码启用 race detector。

#### 6.1 Go race detector

新增 `.github/workflows/go-race.yml`：
- 顶层 name 不设，job name `Go Race`
- 触发：每晚定时 `0 3 * * *` + `workflow_dispatch`
- **不作为 required check**，不使用 label 触发（label 对 fork PR 不友好）
- 步骤：`actions/checkout@v5` + `actions/setup-go@v5`（`go-version-file: server/api/go.mod`，用最高版本统一编译 7 个模块，**因 race detector 用 CGo，需 `CGO_ENABLED=1`**） + 逐个模块执行 `go test -race ./...`
  - 循环遍历 7 个模块（api, socks5-proxy, tunnel, httpclient, ratelimit, recovery, stringutil），每个模块单独跑 race
  - 用 `set +e` + 汇总报告，避免某个模块 race 失败导致后续模块不跑
- 初期 `continue-on-error: true` 收集基线 race 报告
- 范围限定：仅扫描 Go 组件，不声称覆盖 Android/Kotlin 并发问题

> **Go 版本 caveat**：race detector 使用 Go 1.25（取自 `server/api/go.mod`）编译所有模块，但 socks5-proxy/tunnel/shared 的 `go.mod` 声明 `go 1.22`。Go 1.25 的 race runtime 实现可能与 Go 1.22 存在差异（如新增/移除的 race 检查点），因此 race 测试结果反映的是 Go 1.25 runtime 下的行为，**不完全等价于生产环境用 Go 1.22 编译运行时的竞态表现**。此 caveat 必须记录到 `docs/go-race-baseline.md` 中。当三个主模块统一 Go 版本后（见"后续可选优化 #1"），此 caveat 自然消除。

#### 6.2 Android 并发债跟踪

C30、C33、C36 位于 `VpnService.kt` / `Socks5ConnectionPool.kt`，需通过 Android 单测、静态分析或专门并发测试修复。修复后更新 `docs/TECH_DEBT.md` 状态，**不与 `go-race.yml` 的 `continue-on-error` 状态绑定**。

#### 6.3 覆盖率阈值（**改用 diff coverage**）

**原计划的 bug**："阈值取自 Step 5 实测基线"会导致未来无测试的代码 0% 增量直接卡死。

**新方案**：
- **diff coverage**（增量覆盖率）：对 PR 修改行单独要求 ≥ 60%。新代码 0% 测试时直接 fail。
  - Go：使用 `github.com/boumenot/gocover-cobertura` 或类似工具生成 diff coverage 报告；或借助 Codecov/Coveralls 的 `diff` 模式
  - Android：使用 `gradle-jacoco-coverage-check` 插件的 `jacocoDiffCoverageVerification` 任务
- **总体覆盖率阈值**仅作宽松基线（如 ≥ 当前实测值），防止退化，不作为 required check
- 在 `app/build.gradle.kts` 新增 `JacocoDiffCoverageVerification` 任务，diff 阈值 60%
- Go 侧 `make go-coverage-check` 改为 diff coverage 检查

**门禁清单**：
- diff coverage 检查必须 `continue-on-error: false`
- race detector 初期 `continue-on-error: true`，待 Go 侧 race 清零且基线稳定后再考虑合并进 `go-ci.yml`

**验证标准**：
- race detector 能产出报告，且首次运行基线被归档到 `docs/go-race-baseline.md`（**临时基线**，race 清零后删除）
- diff coverage 在 PR 中能正确识别修改行并计算覆盖率

**文档生命周期**：`docs/go-race-baseline.md` 属于临时基线，race 清零并合并进 `go-ci.yml` 后应删除。

---

### Step 7：CI 统一调用 Makefile + 分支保护 + Self-hosted 安全基线

**目标**：本地与 CI 行为一致；新 workflow 成为正式门禁；建立 self-hosted 安全基线。

#### 7.1 CI 统一调用 Makefile

- Android 步骤改为 `make android-all`（Step 1-3 已完成）
- Go matrix 每个 component 调用 `make go-ci-component COMPONENT=${{ matrix.component }}`
- 本地全量检查使用 `make go-ci`（而非 `go-all`）

#### 7.2 分支保护

为 `main` 和 `dev` 配置：
- 要求 PR 才能合并
- 要求至少 1 个 reviewer
- **Required checks 必须从 GitHub branch protection UI 的 dropdown 选择，禁止手填**：
  - `commitlint`（pr-checks.yml）
  - `dependency-review`（pr-checks.yml）
  - `Android Build & Test`（android-ci.yml，**保持原 name**）
  - `Go Server Build (api)`、`Go Server Build (socks5-proxy)`、`Go Server Build (tunnel)`（go-ci.yml，**保持原 name**）
  - **shared 模块 check name**：`Go Server Build (httpclient)` 等（Step 3 拆分后产生的新 check）
- **前置条件**：核心 workflow 必须先在 `dev` 上成功跑过一次，否则 required check 名称不可选

**渐进发布**：
1. 先在 `dev` 启用分支保护验证一周
2. 再推广 `main`
3. 启用前必须确认没有 open PR 仍引用旧 `ci.yml` 的 job name

#### 7.3 Self-hosted runner 安全基线（**新增章节**）

持久 self-hosted runner 暴露以下风险，必须建立基线：

1. **runner 部署隔离**：
   - runner 部署在专用 VM，不与其他服务混部
   - 防火墙：deny inbound、deny outbound 到 169.254.169.254（metadata service）
   - runner 进程以低权限用户运行（非 root）
2. **GitHub Actions 权限收敛**：
   - 仓库 Settings → Actions → Workflow permissions 设为 "Read repository contents and packages permissions"
   - 关闭 "Allow GitHub Actions to create and approve pull requests"
3. **Secrets 管理**：
   - secrets 中不放生产凭证（如 MQTT 私钥、TLS pin）
   - 如必要，用 GitHub Environment + protection rules 限定到特定分支
4. **第三方 actions 分级策略**：
   - 仓库 Settings → Actions → "Allow actions by allowlist" 仅放行必要的 actions
   - **分级固定策略**（兼顾安全与可维护性）：
     - **官方/高可信源（`actions/*`, `gradle/*`, `github/*`）**：使用 major version tag（如 `@v5`），信任其语义化版本承诺
     - **社区常用（`dorny/*`, `wagoid/*`, `coderabbitai/*`）**：使用具体版本 tag（如 `@v3.1.0`），配合 Dependabot 自动升级
     - **小众/首次引入的 action**：固定到 commit SHA，并在 PR 中审查其源码
   - 维护方式：在 Step 7 中同步创建 `.github/dependabot.yml`，配置 GitHub Actions 的 version updates，自动提交 PR 升级 action 版本
   - 不建议全部固定到 commit SHA：维护成本过高，且 Dependabot 对 SHA 的更新支持不如 tag 友好
5. **Fork PR 处理**：
   - 默认 "Require approval for first-time contributors" 开启
   - Fork PR 的 workflow 默认只读 `GITHUB_TOKEN`（GitHub 行为）；显式确认后写入

以上安全基线**必须**作为 Step 7 完成的一部分记录到 `docs/RUNNER_DECISION.md`。

#### 7.4 同步修正 AGENTS.md

第 5 节：H5 已修复，不再列为"关键缺陷（需立即修复）"。

#### 7.5 验证标准

- CI 通过 `make` 调用与本地结果一致
- `dev` 分支保护规则生效，required check 从 dropdown 选择
- `docs/RUNNER_DECISION.md` 包含 self-hosted 安全基线
- 一周后 `main` 分支保护生效

---

## Self-hosted Runner 长期配置（取代原"迁移注意事项"）

> **本节是长期方案**，不是临时兼容。Runner 自身的迁移决策（如未来切回 GitHub 官方 runner）由 `docs/RUNNER_DECISION.md` 维护，不在本计划范围内。

### 1. Runner 工具链（永久要求）

Runner 需要预装：
- **JDK 17**
- **Android SDK**（compileSdk 36；`/opt/android-sdk` 通过 `/etc/environment` 固化）
- **Go 多版本**（1.22、1.25.0，由 `setup-go@v5` 自动安装）
- **GCC**（CGo 依赖，`server/api` 的 sqlite3 测试需要 `CGO_ENABLED=1`）
- **Docker Engine**（Step 4.5 Docker 构建验证需要，确认 `docker info` 可执行且 runner 用户有 docker 组权限）
- **Gradle wrapper**（`android/gradlew` 有执行权限）

### 2. 磁盘清理（自动 + 手动）

**两层清理策略**：

1. **每次 job 后轻量清理**（各 workflow 最后一步，`if: always()`）：
   - Android job：`rm -rf android/b/`（artifact 已上传）
   - Go job：**仅清理 `$GOCACHE`**（编译产物）；**保留 `$GOMODCACHE`** 供 `actions/cache` 本地磁盘命中

2. **每日深度清理**：`.github/workflows/runner-cleanup.yml` 每天 04:00 清理：
   - `$RUNNER_TEMP` 中超过 3 天的 `go-mod-*` / `go-build-*` / `gradle-*` 目录
   - `~/.gradle/caches/build-cache-*` 和 `transforms-*`
   - 超过 7 天的孤立工作目录

**手动清理**（磁盘告警时）：
```bash
# 紧急清理
rm -rf android/b/
df -h
```

### 3. Runner 容量与并发估算

self-hosted runner 当前配置（2026-07 实测）：
- **online runner 数量**：1 台（`ar-npg-sfo3`，机器名 `ailoha-runner-sfo3`）
- **offline runner**：1 台（`ar-NetProxyGateway-sfo3`，已废弃或待清理）
- 标签：`[self-hosted, Linux, X64, do-sfo3]`
- 典型配置估算：4 vCPU，16 GB RAM（需在 runner 上用 `nproc` 和 `free -h` 确认）
- GitHub runner 并发数（单 runner 进程的并发 job 数）：需在 runner 配置文件中确认（默认通常为 1）

> **重要**：单 runner 多进程并发数由 `--max_runners` 参数决定同一台机器上同时跑几个 job。默认值通常是 1。如果机器配置高可以调到 2-3，但需注意磁盘和内存压力。

**单 PR 预期 job 数**（Step 3 拆分后）：
- `Android Build & Test` × 1
- `Go Server Build (api/socks5-proxy/tunnel/httpclient/ratelimit/recovery/stringutil)` × 7
- `commitlint` × 1
- `dependency-review` × 1
- **合计 ~10 job / PR**（其中 7 个 Go job 由 matrix 展开，会依次排队执行）

**并发估算（按单 runner + `max_runners=1` 计）**：
- 1 个 PR → 10 个 job 排队，每个 job 平均 5-15 分钟，总排队+执行约 30-60 分钟
- 2 个并发 PR → 20 个 job 排队，约 60-120 分钟
- **结论**：超过 2 个并发 PR 时排队已较明显。3 个以上 PR 会严重排队。

**降级开关**：
- 当 runner 持续排队 > 30 分钟：
  1. 先启用 `cancel-in-progress: true` 减少旧 run 占用（已在 Step 1.3 启用）
  2. 评估合并 Go matrix（如把 7 个 component 合并为单 job 串行执行），将 job 数从 10 降到 4
  3. 增加第二台 runner（如果 offline 那台能恢复上线）
- 触发条件：runner 排队时长 > 30 分钟持续一周
- **测量方法**：`gh api repos/:owner/:repo/actions/runs --paginate --jq '.workflow_runs[] | {created_at, name, conclusion, run_started_at, updated_at}'` + 自定义脚本统计从 PR 推送到 CI 完成时差

### 4. CI 失败通知（建议，非强制）

当前 PR 失败靠 reviewer 主动看。建议未来加入：
- 失败时通过 `slack` / `email` action 通知 maintainer
- 实施时机：Step 7 后单独 PR

---

## 后续可选优化（未决策，需实测数据）

以下优化在主步骤完成后根据实际运行数据评估，不提前实施：

1. **Go matrix 合并降本评估**：Step 3 拆分后，若 Go 端 CI 成本仍高（平均 > 8 分钟/PR 或 cache 命中率 < 60%），评估合并为单 runner。**前置条件**：先统一三个 `go.mod` 的 Go 版本（当前 api=1.25.0，socks5-proxy/tunnel/shared=1.22）。
2. **深度静态分析**：`staticcheck`（Go）、`detekt`/`ktlint`（Kotlin）。需先评估存量违规量，避免一次性引入大量噪音（与 Step 4.1 同一策略）。
3. **失败通知集成**：Slack/email webhook，Step 7 完成后单独 PR。
4. **Dependabot 配置**：建立 `.github/dependabot.yml` 自动更新依赖，减少 N87/N88 类问题。

---

## 风险与回滚

| 步骤 | 主要风险 | 回滚动作 |
|---|---|---|
| Step 0 | N87 修复引入回归；CRLF renormalize 触发大量文件 diff | revert 提交；N87 修复如失败则降级 `continue-on-error: true` 并在 ISSUES.md 显式标注 |
| Step 0 | PR #65 合并时与 dev 冲突 | 解决冲突（冲突应集中在 ci.yml） |
| Step 1 | cache 显式隔离目录在持久 runner 上仍有竞态 | revert env 块；评估换 `setup-go` 默认行为 |
| Step 1 | `cancel-in-progress: true` 误取消需要的 run | 改为 `false`（接受排队） |
| Step 1 | `gcc` 在 runner 上缺失 | 安装 gcc；或临时在 `server/api` 单测前 `CGO_ENABLED=0` + 跳过 sqlite3 测试 |
| Step 2 | shared 模块 `go mod tidy` 引入意外依赖 | revert 提交，重新 `go mod tidy`；`--continue` 隐藏失败时去掉 |
| Step 3 | workflow 拆分后损坏；旧 PR 复活 `ci.yml`；required check 名称不匹配 | GitHub UI 禁用对应 workflow，或 revert workflow 文件提交；要求旧 PR rebase；先跑一次让 GitHub 生成 context 列表再配置保护 |
| Step 3 | `runner-cleanup.yml` 误删正在使用的文件 | 调整 `find -mtime` 阈值；首次执行前 dry-run 打印待删文件 |
| Step 4 | `gofmt`/`go vet` 暴露大量存量问题；gitleaks 误报/license | 将对应 step 改回 `continue-on-error: true`；本地修复后再启用 |
| Step 4 | `dependency-review` 误报阻塞合法 PR | 在 PR 描述中允许 maintainer 标注"已知问题"豁免 |
| Step 5 | 新增测试本身有 bug | revert 测试文件提交 |
| Step 5 | `private` → `internal` 可见性变更导致内部 API 被意外调用 | 添加 KDoc 警告注释 + `@VisibleForTesting`；Code Review 重点关注 |
| Step 6 | diff coverage 配置错误，误报高/低 | 调整 jacoco diff 报告生成参数；临时 `continue-on-error: true` |
| Step 6 | race detector 暴露未修复竞态 | 保持 `continue-on-error: true`；基线归档到 `docs/go-race-baseline.md` |
| Step 7 | 分支保护配置错误阻塞合并 | GitHub UI 临时关闭保护规则 |
| Step 7 | self-hosted runner 部署未遵循安全基线 | 按 7.3 章节逐项检查；在 `docs/RUNNER_DECISION.md` 记录当前基线 |
| Step 4 | Docker 构建失败（Dockerfile 过时或缺少依赖） | `continue-on-error: true` 先跑基线；修复 Dockerfile 后再收紧门禁 |
| 通用 | 持久 self-hosted runner 磁盘/并发容量不足导致排队或构建失败 | 触发 `runner-cleanup.yml`；增加 runner 资源；按容量估算评估降级 |
| 通用 | Go 1.25.0 与 1.22 模块混编出现 toolchain 兼容性错误 | 统一 `go.mod` 版本；或在 CI 中为不同 component 安装对应 Go 版本 |
| 通用 | 过渡期内 required check 尚未出现在分支上导致 PR 无法合并 | 临时在分支保护中移除该 check，或推送一个空 commit 触发 workflow 生成 context |
| 通用 | self-hosted runner 被恶意 PR 攻陷 | 启用 7.3 安全基线；监控 runner 异常行为（CPU/网络） |

---

## 文档生命周期

本文件是临时性实施计划。实施完成后：

1. **删除本文件**（`docs/CI_REFACTOR_PLAN.md`）
2. **新建 `docs/RUNNER_DECISION.md`**（**本计划新增**），包含：
   - self-hosted runner 选型决策与长期承诺
   - Step 7.3 的安全基线
   - 容量估算与降级策略
   - runner 迁移决策流程（未来若切回 GitHub 官方 runner）
3. **关键决策补录到 `docs/DECISIONS.md`**：
   - workflow 拆分决策
   - 路径过滤策略
   - 取消进行中运行的并发策略
   - diff coverage 取代总覆盖率阈值
4. **已确认 bug 登记到 `docs/ISSUES.md`**（按 AGENTS.md 第 11 节要求）：
   - N87：Android `testDebugUnitTest` 静默挂死（含根因和修复）
   - N88：`server/api/main.go` CRLF 伪 diff
   - CGo/SQLite `CGO_ENABLED=0` 缺失
5. **同步更新 `AGENTS.md` 第 5 节**：H5 已修复，不再列为"关键缺陷（需立即修复）"
6. **删除/归档临时文档**：
   - `docs/go-race-baseline.md`（Step 6 完成后归档或删除）
