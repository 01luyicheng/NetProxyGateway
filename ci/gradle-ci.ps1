# gradle-ci.ps1 — GitHub Actions 远程构建封装
# 用法: .\gradle-ci.ps1 assembleDebug

param(
    [Parameter(Position=0)]
    [string]$Task = "assembleDebug",
    
    [switch]$Watch,
    [switch]$Install,
    [switch]$Status
)

# ============ 配置区 ============
$CI_REPO = "luyicheng/android-ci"  # 你的公开 CI 仓库
$SOURCE_BRANCH = "main"             # 源代码分支
$CI_BRANCH = "ci/build"             # CI 专用分支（自动创建）
# ================================

$ErrorActionPreference = "Stop"

function Push-ToCI {
    # 添加 .gitignore 排除构建产物
    $gitignore = @"
build/
.gradle/
local.properties
*.apk
*.aab
*.iml
.idea/
*.hprof
"@
    
    if (-not (Test-Path ".gitignore") -or (Get-Content ".gitignore" -Raw) -ne $gitignore) {
        $gitignore | Out-File -FilePath ".gitignore" -Encoding utf8
    }
    
    Write-Host "[1/4] Pushing to CI repo..." -ForegroundColor Cyan
    
    # 确保有 CI 仓库的 remote
    $remotes = git remote -v 2>$null
    if ($remotes -notmatch "ci-repo") {
        git remote add ci-repo "https://github.com/$CI_REPO.git" 2>$null
    }
    
    # 推送当前代码到 CI 分支
    git add -A
    $commitMsg = "ci: build $Task $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    git commit -m $commitMsg --allow-empty 2>$null
    git push ci-repo "HEAD:$CI_BRANCH" --force 2>$null
    
    Write-Host "  Pushed to $CI_REPO (branch: $CI_BRANCH)" -ForegroundColor Green
}

function Start-CIBuild {
    Write-Host "[2/4] Triggering GitHub Actions..." -ForegroundColor Cyan
    
    # 通过 workflow_dispatch 触发构建
    $response = gh api repos/$CI_REPO/actions/workflows/android-ci.yml/dispatches `
        -f ref=$CI_BRANCH `
        -f "inputs[gradle_task]=$Task" 2>&1
    
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  Failed to trigger workflow. Check: gh auth status" -ForegroundColor Red
        exit 1
    }
    
    Write-Host "  Workflow triggered!" -ForegroundColor Green
}

function Watch-Build {
    Write-Host "[3/4] Watching build..." -ForegroundColor Cyan
    Write-Host ""
    
    # 等待最新的 run 出现
    Start-Sleep -Seconds 5
    
    $run = gh run list --repo $CI_REPO --branch $CI_BRANCH --limit 1 --json databaseId,status,conclusion --jq '.[0]'
    $runId = ($run | ConvertFrom-Json).databaseId
    
    if ($runId) {
        Write-Host "  Build ID: $runId" -ForegroundColor Yellow
        Write-Host "  URL: https://github.com/$CI_REPO/actions/runs/$runId" -ForegroundColor Yellow
        Write-Host ""
        
        if ($Watch) {
            Write-Host "  Waiting for completion..." -ForegroundColor Cyan
            gh run watch $runId --repo $CI_REPO --exit-status
            
            $result = gh run view $runId --repo $CI_REPO --json conclusion --jq '.conclusion'
            if ($result -eq "success") {
                Write-Host "  Build succeeded!" -ForegroundColor Green
                return $true
            } else {
                Write-Host "  Build failed!" -ForegroundColor Red
                return $false
            }
        }
        return $true
    } else {
        Write-Host "  No run found yet. Check GitHub Actions tab." -ForegroundColor Yellow
        return $false
    }
}

function Download-APK {
    Write-Host "[4/4] Downloading APK..." -ForegroundColor Cyan
    
    $run = gh run list --repo $CI_REPO --branch $CI_BRANCH --limit 1 --json databaseId --jq '.[0].databaseId'
    
    if ($run) {
        $tempDir = Join-Path $env:TEMP "ci-build-$(Get-Date -Format 'yyyyMMddHHmmss')"
        New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
        
        gh run download $run --repo $CI_REPO --name "app-debug" --dir $tempDir 2>$null
        
        $apk = Get-ChildItem -Path $tempDir -Filter "*.apk" | Select-Object -First 1
        if ($apk) {
            Write-Host "  Downloaded: $($apk.FullName)" -ForegroundColor Green
            
            if ($Install) {
                Write-Host "  Installing to device..." -ForegroundColor Cyan
                adb install -r $apk.FullName
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "  Installed successfully!" -ForegroundColor Green
                } else {
                    Write-Host "  Install failed. Is device connected?" -ForegroundColor Red
                }
            }
            
            return $apk.FullName
        }
    }
    
    Write-Host "  No APK found. Build may still be running." -ForegroundColor Yellow
    return $null
}

function Show-Status {
    Write-Host "Recent builds for $CI_REPO ($CI_BRANCH):" -ForegroundColor Cyan
    gh run list --repo $CI_REPO --branch $CI_BRANCH --limit 5
}

# ============ 主逻辑 ============

if ($Status) {
    Show-Status
    exit 0
}

Write-Host ""
Write-Host "=== Android CI Build ===" -ForegroundColor Magenta
Write-Host "Task: $Task" -ForegroundColor Yellow
Write-Host ""

Push-ToCI
Start-CIBuild

if ($Watch) {
    $success = Watch-Build
    if ($success -and $Install) {
        Download-APK
    }
} else {
    Write-Host ""
    Write-Host "Build triggered. Monitor at:" -ForegroundColor Green
    Write-Host "  https://github.com/$CI_REPO/actions" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Commands:" -ForegroundColor Cyan
    Write-Host "  .\gradle-ci.ps1 -Status          # 查看构建状态" -ForegroundColor White
    Write-Host "  .\gradle-ci.ps1 -Watch           # 等待构建完成" -ForegroundColor White
    Write-Host "  .\gradle-ci.ps1 -Watch -Install  # 等待完成并安装" -ForegroundColor White
}

Write-Host ""
