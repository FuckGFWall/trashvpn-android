$ErrorActionPreference = 'Stop'
$version = 'v0.3.14'
$expected = '7c3a68a4ef9afcb5018e167dc70fba7485652d0a0e29ba006703cf00fed9e230'
$name = "libmihomo-android-$version.aar"
$deps = Join-Path (Split-Path -Parent $PSScriptRoot) '.deps'
$out = Join-Path $deps $name
New-Item -ItemType Directory -Path $deps -Force | Out-Null
if ((Test-Path -LiteralPath $out) -and (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash -eq $expected) {
    Write-Host "Verified $name"
    exit 0
}
$partial = "$out.download"
$url = "https://github.com/oviron/libmihomo-android/releases/download/$version/$name"
& curl.exe --fail --silent --show-error --location --retry 2 --output $partial $url
if ($LASTEXITCODE -ne 0) { throw 'Mihomo download failed' }
if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $expected) {
    throw 'Mihomo AAR SHA-256 mismatch; refusing to use this download'
}
Move-Item -LiteralPath $partial -Destination $out -Force
Write-Host "Verified $name"
