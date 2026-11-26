[CmdletBinding()]
param(
    [string]$Gradle,
    [switch]$Offline,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $Gradle) { $Gradle = Join-Path $root 'gradlew.bat' }
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $properties = Join-Path $root 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($line) { $sdk = $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
}
if (-not $sdk) { throw 'Set ANDROID_HOME to the local Android SDK.' }
$signer = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Sort-Object -Descending | Select-Object -First 1
if (-not $signer) { throw 'Install Android SDK Build Tools (apksigner).' }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/keytool.exe' } else { 'keytool.exe' }
$keyFolder = Join-Path $root '.local'
$keyPath = Join-Path $keyFolder 'trashvpn-release.p12'
$passwordPath = Join-Path $keyFolder 'trashvpn-release.password.dpapi'
$keyAlias = 'trashvpn-release'
$secretVariable = 'TRASHVPN_APK_PASSWORD'
$original = [Environment]::GetEnvironmentVariable($secretVariable, 'Process')

Push-Location -LiteralPath $root
try {
    $tasks = @('--no-daemon', ':app:assembleRelease')
    if ($Offline) { $tasks = @('--offline') + $tasks }
    if ($Check) { $tasks += @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:assembleDebugAndroidTest', ':app:lintDebug') }
    & $Gradle @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }

    $keyExists = Test-Path -LiteralPath $keyPath -PathType Leaf
    $savedPassword = Test-Path -LiteralPath $passwordPath -PathType Leaf
    if (-not $keyExists -and $savedPassword) {
        throw 'The protected password exists but the release key is missing. Restore the same key; do not create a replacement.'
    }
    if (-not $original) {
        if ($savedPassword) {
            $secret = ConvertTo-SecureString -String ((Get-Content -LiteralPath $passwordPath -Raw).Trim())
            $plain = [Net.NetworkCredential]::new('', $secret).Password
        } elseif (-not $keyExists) {
            $bytes = New-Object byte[] 32
            $random = [Security.Cryptography.RandomNumberGenerator]::Create()
            try { $random.GetBytes($bytes); $plain = [Convert]::ToBase64String($bytes) }
            finally { $random.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
        } else {
            throw 'Set TRASHVPN_APK_PASSWORD securely or restore the account-specific DPAPI password file.'
        }
        [Environment]::SetEnvironmentVariable($secretVariable, $plain, 'Process')
        $plain = $null
    }
    if (-not $keyExists) {
        New-Item -ItemType Directory -Force -Path $keyFolder | Out-Null
        $staging = Join-Path $keyFolder ([Guid]::NewGuid().ToString() + '.staged.p12')
        $keyOptions = @('-genkeypair', '-noprompt', '-alias', $keyAlias, '-dname', 'CN=TrashVPN',
            '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA', '-validity', '10958',
            '-storetype', 'PKCS12', '-keystore', $staging, '-storepass:env', $secretVariable, '-keypass:env', $secretVariable)
        & $keytool @keyOptions
        if ($LASTEXITCODE -ne 0) { throw 'Could not generate the release signing key.' }
        if (-not $original) {
            $secret = ConvertTo-SecureString -AsPlainText -Force -String ([Environment]::GetEnvironmentVariable($secretVariable))
            ConvertFrom-SecureString -SecureString $secret | Set-Content -LiteralPath $passwordPath -Encoding ascii
        }
        Move-Item -LiteralPath $staging -Destination $keyPath
    }

    $release = Join-Path $root 'app/build/outputs/apk/release'
    $unsigned = Join-Path $release 'app-release-unsigned.apk'
    $signed = Join-Path $release 'TrashVPN-release.apk'
    $signOptions = @('sign', '--ks', $keyPath, '--ks-type', 'PKCS12', '--ks-key-alias', $keyAlias,
        '--ks-pass', "env:$secretVariable", '--key-pass', "env:$secretVariable",
        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $signed, $unsigned)
    & $signer @signOptions
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $signer verify --verbose --print-certs $signed
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Get-FileHash -LiteralPath $signed -Algorithm SHA256
} finally {
    [Environment]::SetEnvironmentVariable($secretVariable, $original, 'Process')
    Pop-Location
}
