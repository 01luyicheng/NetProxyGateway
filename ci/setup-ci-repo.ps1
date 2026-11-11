# setup-ci-repo.ps1 — 初始化 CI 仓库
# 在 Windows 上运行一次

param(
    [string]$CIRepo = "luyicheng/android-ci"
)

$ErrorActionPreference = "Stop"

Write-Host "=== Setting up Android CI Repository ===" -ForegroundColor Magenta
Write-Host ""

# 1. 检查 gh CLI
Write-Host "[1/6] Checking GitHub CLI..." -ForegroundColor Cyan
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "  GitHub CLI not found. Install from: https://cli.github.com/" -ForegroundColor Red
    exit 1
}
$ghAuth = gh auth status 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Not logged in. Run: gh auth login" -ForegroundColor Red
    exit 1
}
Write-Host "  GitHub CLI ready!" -ForegroundColor Green

# 2. 创建 CI 仓库（如果不存在）
Write-Host "[2/6] Creating CI repository..." -ForegroundColor Cyan
$repoExists = gh repo view $CIRepo 2>&1
if ($LASTEXITCODE -ne 0) {
    gh repo create $CIRepo --public --description "Android CI Build Repository" --clone
    Write-Host "  Created $CIRepo" -ForegroundColor Green
} else {
    Write-Host "  Repository $CIRepo already exists" -ForegroundColor Yellow
}

# 3. 克隆 CI 仓库
Write-Host "[3/6] Cloning CI repository..." -ForegroundColor Cyan
$ciDir = Join-Path $env:USERPROFILE "android-ci"
if (-not (Test-Path $ciDir)) {
    git clone "https://github.com/$CIRepo.git" $ciDir
}
Set-Location $ciDir
Write-Host "  Cloned to $ciDir" -ForegroundColor Green

# 4. 创建 GitHub Actions workflow
Write-Host "[4/6] Creating workflow..." -ForegroundColor Cyan
$workflowDir = Join-Path $ciDir ".github\workflows"
New-Item -ItemType Directory -Path $workflowDir -Force | Out-Null

$workflowContent = @"
name: Android CI

on:
  push:
    branches: [ ci/build ]
  workflow_dispatch:
    inputs:
      gradle_task:
        description: 'Gradle task to run'
        required: false
        default: 'assembleDebug'
        type: choice
        options:
          - assembleDebug
          - assembleRelease
          - testDebugUnitTest
          - lintDebug

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    
    steps:
    - uses: actions/checkout@v4
      with:
        ref: ${{ github.ref }}
        fetch-depth: 0
    
    - uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - uses: android-actions/setup-android@v3
    
    - uses: actions/cache@v4
      with:
        path: |
          ~/.gradle/caches
          ~/.gradle/wrapper
        key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}
    
    - run: chmod +x gradlew
    
    - name: Run Gradle
      run: |
        TASK="${{ github.event.inputs.gradle_task || 'assembleDebug' }}"
        ./gradlew $TASK --no-daemon
    
    - uses: actions/upload-artifact@v4
      if: contains(github.event.inputs.gradle_task || 'assembleDebug', 'Debug')
      with:
        name: app-debug
        path: app/build/outputs/apk/debug/*.apk
        retention-days: 7
"@

$workflowContent | Out-File -FilePath (Join-Path $workflowDir "android-ci.yml") -Encoding utf8
Write-Host "  Created workflow file" -ForegroundColor Green

# 5. 创建 .gitignore
Write-Host "[5/6] Creating .gitignore..." -ForegroundColor Cyan
$gitignore = @"
build/
.gradle/
local.properties
*.apk
*.aab
*.iml
.idea/
*.hprof
local.properties
"@
$gitignore | Out-File -FilePath (Join-Path $ciDir ".gitignore") -Encoding utf8
Write-Host "  Created .gitignore" -ForegroundColor Green

# 6. 初始提交
Write-Host "[6/6] Initial commit..." -ForegroundColor Cyan
git add -A
git commit -m "Initial CI setup"
git branch -M ci/build
git push -u origin ci/build
Write-Host "  Pushed initial commit" -ForegroundColor Green

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Run: .\setup-aliases.ps1 (in your project directory)" -ForegroundColor White
Write-Host "  2. Copy gradle-ci.ps1 to your user profile" -ForegroundColor White
Write-Host "  3. Use 'ci assembleDebug' to start building!" -ForegroundColor White
