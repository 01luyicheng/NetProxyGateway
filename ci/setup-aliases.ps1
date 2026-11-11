# setup-aliases.ps1 — 设置 PowerShell 别名
# 在 PowerShell profile 中添加以下内容

$profileContent = @"

# ============ Android CI Aliases ============
# 用法: ci assembleDebug 或 ci assembleRelease

function Invoke-CIBuild {
    param(
        [string]$Task = "assembleDebug",
        [switch]$Watch,
        [switch]$Install,
        [switch]$Status
    )
    
    $scriptPath = Join-Path $env:USERPROFILE "gradle-ci.ps1"
    
    if ($Status) {
        & $scriptPath -Status
    } elseif ($Watch) {
        & $scriptPath -Task $Task -Watch -Install:$Install
    } else {
        & $scriptPath -Task $Task
    }
}

# 设置别名
Set-Alias -Name ci -Value Invoke-CIBuild

# 快捷命令
function ci-debug { ci assembleDebug -Watch }
function ci-release { ci assembleRelease -Watch }
function ci-test { ci testDebugUnitTest -Watch }
function ci-lint { ci lintDebug -Watch }
function ci-install { ci assembleDebug -Watch -Install }
function ci-status { ci -Status }

# ============ End CI Aliases ============

"@

# 备份现有 profile
$profilePath = $PROFILE.CurrentUserAllHosts
$backupPath = "$profilePath.bak"

if (Test-Path $profilePath) {
    Copy-Item $profilePath $backupPath -Force
    Write-Host "Backed up existing profile to $backupPath" -ForegroundColor Yellow
}

# 添加别名到 profile
$profileContent | Out-File -FilePath $profilePath -Append -Encoding utf8

Write-Host ""
Write-Host "=== Setup Complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "New commands available:" -ForegroundColor Cyan
Write-Host "  ci assembleDebug        # 构建 Debug APK" -ForegroundColor White
Write-Host "  ci assembleRelease      # 构建 Release APK" -ForegroundColor White
Write-Host "  ci testDebugUnitTest    # 运行单元测试" -ForegroundColor White
Write-Host "  ci lintDebug            # 运行 Lint 检查" -ForegroundColor White
Write-Host ""
Write-Host "Shortcuts:" -ForegroundColor Cyan
Write-Host "  ci-debug                # 快速构建 Debug" -ForegroundColor White
Write-Host "  ci-release              # 快速构建 Release" -ForegroundColor White
Write-Host "  ci-test                 # 快速测试" -ForegroundColor White
Write-Host "  ci-lint                 # 快速 Lint" -ForegroundColor White
Write-Host "  ci-install              # 构建并安装到手机" -ForegroundColor White
Write-Host "  ci-status               # 查看构建状态" -ForegroundColor White
Write-Host ""
Write-Host "Examples:" -ForegroundColor Cyan
Write-Host "  ci assembleDebug -Watch           # 构建并等待完成" -ForegroundColor White
Write-Host "  ci assembleRelease -Watch -Install # 构建 Release 并安装" -ForegroundColor White
Write-Host ""
Write-Host "Please restart PowerShell or run: . `$PROFILE" -ForegroundColor Yellow
