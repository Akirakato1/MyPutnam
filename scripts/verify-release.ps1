param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ApkPath
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$apk = Get-Item -LiteralPath $ApkPath
$expectedReleasePath = Join-Path $repoRoot "content-src\releases\putnam-2020-2025-v0.4.0.json"
$expectedRelease = Get-Content -LiteralPath $expectedReleasePath -Raw | ConvertFrom-Json

function Find-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $localProperties = Join-Path $repoRoot "local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $candidate = $sdkLine.Substring("sdk.dir=".Length).Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $candidate -PathType Container) {
                return (Resolve-Path -LiteralPath $candidate).Path
            }
        }
    }

    throw "Android SDK not found. Set ANDROID_SDK_ROOT or add sdk.dir to local.properties."
}

$sdkRoot = Find-AndroidSdk
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot "build-tools") -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $buildTools) {
    throw "No Android SDK build-tools installation found under $sdkRoot."
}

$apksigner = Join-Path $buildTools.FullName "apksigner.bat"
$aapt2 = Join-Path $buildTools.FullName "aapt2.exe"
foreach ($tool in @($apksigner, $aapt2)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android tool not found: $tool"
    }
}

$signatureOutput = & $apksigner verify --verbose --print-certs $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "APK signature verification failed:`n$signatureOutput"
}

$badging = & $aapt2 dump badging $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    throw "APK manifest inspection failed:`n$badging"
}

$checks = [ordered]@{
    "application ID" = $badging -match "package: name='com\.putnamworkbook\.offline'"
    "version code $($expectedRelease.appVersionCode)" = $badging -match "versionCode='$([regex]::Escape([string]$expectedRelease.appVersionCode))'"
    "version name $($expectedRelease.appVersionName)" = $badging -match "versionName='$([regex]::Escape([string]$expectedRelease.appVersionName))'"
    "minimum SDK 29" = $badging -match "minSdkVersion:'29'"
    "target SDK 36" = $badging -match "targetSdkVersion:'36'"
    "no INTERNET permission" = $badging -notmatch "uses-permission: name='android\.permission\.INTERNET'"
    "retained signing certificate" = $signatureOutput -match "(?i)Signer #1 certificate SHA-256 digest:\s*f9e07df7b54ed8f3c893303d4724a5387eec18d6bc14f7ed774e8240a2822a23"
}
foreach ($check in $checks.GetEnumerator()) {
    if (-not $check.Value) {
        throw "Release check failed: $($check.Key)."
    }
}

$sidecarPath = "$($apk.FullName).sha256"
if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf)) {
    throw "SHA-256 sidecar not found: $sidecarPath"
}
$expectedHash = ((Get-Content -LiteralPath $sidecarPath -Raw).Trim() -split '\s+')[0].ToUpperInvariant()
$actualHash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
if ($expectedHash -ne $actualHash) {
    throw "SHA-256 mismatch: expected $expectedHash, got $actualHash."
}

$python = Get-Command python -ErrorAction Stop
$auditor = Join-Path $repoRoot "tools\content\src\audit-packaged-content.py"
$savedErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $auditOutput = & $python.Source $auditor --apk $apk.FullName --expected-release $expectedReleasePath 2>&1 | Out-String
    $auditExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $savedErrorActionPreference
}
if ($auditExitCode -ne 0) {
    throw "Packaged content audit failed:`n$auditOutput"
}

Write-Host "Release verification passed."
Write-Host "  APK: $($apk.FullName)"
Write-Host "  SHA-256: $actualHash"
Write-Host "  Application ID: com.putnamworkbook.offline"
Write-Host "  Version: $($expectedRelease.appVersionName) (code $($expectedRelease.appVersionCode))"
Write-Host "  SDK range: 29 to 36"
Write-Host "  INTERNET permission: absent"
Write-Host "  Signer SHA-256: f9e07df7b54ed8f3c893303d4724a5387eec18d6bc14f7ed774e8240a2822a23"
Write-Host "  Content audit: $($auditOutput.Trim())"
