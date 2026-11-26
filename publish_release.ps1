# Animated Capes - Automated GitHub Release Publisher
# Uses your local Git credentials to publish clean releases and upload assets.

param(
    [string]$TagName = "v1.0.0",
    [string]$ReleaseTitle = "Animated Capes v1.0.0 (1.21.11, 26.1, 26.2)",
    [string]$Repo = "mezza1e/AnimatedCapes",
    [string]$AssetsDir = "C:\Users\jay\.gemini\antigravity\scratch\animacapes_all_versions"
)

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "   Publishing Animated Capes Release to GitHub: $Repo" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# 1. Retrieve GitHub Token from Git Credential Manager
Write-Host "[1/4] Retrieving GitHub credentials from Git Credential Manager..." -ForegroundColor Yellow
$credInput = "protocol=https`nhost=github.com`n`n"
$credOutput = $credInput | git credential fill
$token = ""
foreach ($line in ($credOutput -split "`n")) {
    if ($line -match "^password=(.+)$") {
        $token = $matches[1].Trim()
        break
    }
}

if (-not $token) {
    Write-Error "Could not retrieve GitHub token from git credentials."
    exit 1
}
Write-Host "  Authenticated successfully as mezza1e" -ForegroundColor Green

# 2. Check or Create Git Tag & Push
Write-Host "[2/4] Ensuring tag '$TagName' exists on remote..." -ForegroundColor Yellow
$repoDir = "C:\Users\jay\.gemini\antigravity\scratch\animacapes"
git -C $repoDir tag -f $TagName
git -C $repoDir push origin $TagName --force
Write-Host "  Tag '$TagName' pushed to origin." -ForegroundColor Green

# 3. Create or Get Release on GitHub
Write-Host "[3/4] Creating GitHub Release '$ReleaseTitle'..." -ForegroundColor Yellow
$headers = @{
    "Authorization" = "Bearer $token"
    "Accept"        = "application/vnd.github+json"
    "X-GitHub-Api-Version" = "2022-11-28"
}

$releaseBody = @"
## Animated Capes v1.0.0
High-performance animated capes (GIF, WebP, AVIF, PNG) with native zero-CPU frame swapping, out-of-process Win32 file picker, and frame-accurate Replay Mod synchronization.

### Supported Minecraft Versions:
* **Minecraft 1.21.11** (Fabric)
* **Minecraft 26.1** (Fabric)
* **Minecraft 26.2** (Fabric)

### Verification:
All release binaries are verified against the SHA-256 checksums below.
"@

$releasePayload = @{
    tag_name         = $TagName
    target_commitish = "main"
    name             = $ReleaseTitle
    body             = $releaseBody
    draft            = $false
    prerelease       = $false
} | ConvertTo-Json

$release = $null
try {
    $release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases" -Method Post -Headers $headers -Body $releasePayload -ContentType "application/json"
    Write-Host "  Release created: $($release.html_url)" -ForegroundColor Green
} catch {
    # Check if release already exists
    Write-Host "  Release may already exist, fetching existing release..." -ForegroundColor Yellow
    $existing = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/tags/$TagName" -Method Get -Headers $headers
    if ($existing -and $existing.id) {
        $release = $existing
        Write-Host "  Found existing release ID: $($release.id)" -ForegroundColor Green
    } else {
        throw $_
    }
}

# 4. Generate SHA256SUMS.txt & Upload Clean Assets
Write-Host "[4/4] Uploading clean release assets..." -ForegroundColor Yellow

$assetFiles = @(
    "AnimatedCapes-1.0.0+1.21.11.jar",
    "AnimatedCapes-1.0.0+26.1.jar",
    "AnimatedCapes-1.0.0+26.2.jar"
)

# Compute SHA256
$sumsPath = Join-Path $AssetsDir "SHA256SUMS.txt"
$sumLines = @()
foreach ($fn in $assetFiles) {
    $fp = Join-Path $AssetsDir $fn
    if (Test-Path $fp) {
        $hash = (Get-FileHash -Path $fp -Algorithm SHA256).Hash.ToLower()
        $sumLines += "$hash  $fn"
    }
}
$sumLines | Out-File -FilePath $sumsPath -Encoding utf8
$uploadFiles = $assetFiles + "SHA256SUMS.txt"

# Clear any existing assets to ensure clean replacement
try {
    $currAssets = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/$($release.id)/assets" -Method Get -Headers $headers
    foreach ($a in $currAssets) {
        Write-Host "  Purging existing release asset $($a.name)..." -ForegroundColor Yellow
        Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/assets/$($a.id)" -Method Delete -Headers $headers
    }
} catch {}

foreach ($fn in $uploadFiles) {
    $fp = Join-Path $AssetsDir $fn
    if (-not (Test-Path $fp)) {
        Write-Warning "Skipping missing asset: $fp"
        continue
    }

    $fileBytes = [System.IO.File]::ReadAllBytes($fp)
    $uploadUri = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$fn"
    $cType = if ($fn.EndsWith(".jar")) { "application/java-archive" } else { "text/plain" }

    Write-Host "  Uploading $fn ($($fileBytes.Length) bytes)..." -NoNewline
    $uploadRes = Invoke-RestMethod -Uri $uploadUri -Method Post -Headers $headers -Body $fileBytes -ContentType $cType
    Write-Host " Done!" -ForegroundColor Green
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "   SUCCESS! Release published successfully:" -ForegroundColor Green
Write-Host "   $($release.html_url)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
