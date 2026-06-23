param(
    [string] $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [switch] $Clean
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$extensions = @(
    @{
        Slug = "ublock-origin"
        Id = "uBlock0@raymondhill.net"
        Name = "uBlock Origin"
        Version = "1.71.0"
        Url = "https://addons.mozilla.org/firefox/downloads/file/4814095/ublock_origin-1.71.0.xpi"
        Sha256 = "47f788a1fc2c014830b30bb0ef9588615701b98c5265fb19b8cf4ba779849feb"
    },
    @{
        Slug = "cookie-editor"
        Id = "{c3c10168-4186-445c-9c5b-63f12b8e2c87}"
        Name = "Cookie-Editor"
        Version = "1.13.0"
        Url = "https://addons.mozilla.org/firefox/downloads/file/4241002/cookie_editor-1.13.0.xpi"
        Sha256 = "3d6fd83a8343dfa5e4461d83c2856264fb74b36b1c165305168d013f4831dbb0"
    }
)

$cacheDir = Join-Path $ProjectRoot "extensions-cache"
$assetDir = Join-Path $ProjectRoot "app/src/main/assets/extensions"
$metadataPath = Join-Path $ProjectRoot "app/src/main/assets/extensions.json"

New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Remove-DirectoryIfPresent {
    param([string] $Path)
    if (Test-Path -LiteralPath $Path) {
        $resolvedProject = (Resolve-Path -LiteralPath $ProjectRoot).Path
        $resolvedTarget = (Resolve-Path -LiteralPath $Path).Path
        if (-not $resolvedTarget.StartsWith($resolvedProject, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove path outside project: $resolvedTarget"
        }
        Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
}

function Expand-Xpi {
    param(
        [string] $Archive,
        [string] $Destination
    )
    Remove-DirectoryIfPresent -Path $Destination
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path -LiteralPath $Archive).Path, $Destination)
}

function Read-ManifestId {
    param([string] $ExtensionDirectory)
    $manifestPath = Join-Path $ExtensionDirectory "manifest.json"
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.browser_specific_settings.gecko.id) {
        return $manifest.browser_specific_settings.gecko.id
    }
    if ($manifest.browser_specific_settings.gecko_android.id) {
        return $manifest.browser_specific_settings.gecko_android.id
    }
    if ($manifest.applications.gecko.id) {
        return $manifest.applications.gecko.id
    }
    throw "No Gecko extension ID found in $manifestPath"
}

if ($Clean) {
    Remove-DirectoryIfPresent -Path $assetDir
    New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
}

$metadata = @()
foreach ($extension in $extensions) {
    $fileName = "$($extension.Slug)-$($extension.Version).xpi"
    $downloadPath = Join-Path $cacheDir $fileName
    $destination = Join-Path $assetDir $extension.Slug

    if (-not (Test-Path -LiteralPath $downloadPath)) {
        Invoke-WebRequest -Uri $extension.Url -OutFile $downloadPath
    }

    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $downloadPath).Hash.ToLowerInvariant()
    if ($actualHash -ne $extension.Sha256) {
        throw "Checksum mismatch for $($extension.Name). Expected $($extension.Sha256), got $actualHash"
    }

    Expand-Xpi -Archive $downloadPath -Destination $destination

    $manifestId = Read-ManifestId -ExtensionDirectory $destination
    if ($manifestId -ne $extension.Id) {
        throw "Manifest ID mismatch for $($extension.Name). Expected $($extension.Id), got $manifestId"
    }

    $metadata += [ordered]@{
        id = $extension.Id
        slug = $extension.Slug
        displayName = $extension.Name
        version = $extension.Version
        sha256 = $extension.Sha256
        sourceUrl = $extension.Url
        assetUri = "resource://android/assets/extensions/$($extension.Slug)/"
    }
}

$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8
Write-Host "Fetched and unpacked $($metadata.Count) extension(s) into $assetDir"
