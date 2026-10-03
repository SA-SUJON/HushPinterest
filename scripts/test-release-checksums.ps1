<# Real offline signature contracts. Every private test key stays in its own temporary keyring. #>
[CmdletBinding()]
param([string]$Gpg = 'gpg')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-checksums.ps1')

function Assert-ChecksumContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-ChecksumRefusal([scriptblock]$Action, [string]$Pattern = '*') {
    $refused = $false
    try { & $Action | Out-Null }
    catch {
        if ($_.Exception.Message -notlike $Pattern) { throw }
        $refused = $true
    }
    if (-not $refused) { throw "Expected checksum refusal matching $Pattern" }
}
function Invoke-ChecksumTestGpg([string]$KeyringHome, [string[]]$Arguments) {
    $result = Invoke-ReleaseChecksumGpg $Gpg (@(Get-ReleaseChecksumGpgArguments $KeyringHome) + $Arguments)
    if ($result.ExitCode -ne 0) { throw "Test GPG operation failed: $($result.Output -join ' ')" }
    return $result.Output
}
function Write-ChecksumTestSignature([string]$KeyringHome, [string]$Fingerprint, [string]$Payload, [string]$Signature) {
    Invoke-ChecksumTestGpg $KeyringHome @('--armor', '--digest-algo', 'SHA256', '--local-user', $Fingerprint,
        '--output', $Signature, '--detach-sign', $Payload) | Out-Null
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('hushpinterest-checksum-contract-' + [guid]::NewGuid().ToString('N'))
$homes = @((Join-Path $work 'key-one'), (Join-Path $work 'key-two'))
$assets = Join-Path $work 'assets'
$trusted = Join-Path $work 'trusted'
foreach ($directory in @($work, $assets, $trusted) + $homes) { [void][IO.Directory]::CreateDirectory($directory) }
try {
    $names = @('release-receipt-0.0.2.json', 'patches-0.0.2.mpp', 'patches-0.0.2.cdx.json')
    foreach ($name in $names) { [IO.File]::WriteAllText((Join-Path $assets $name), "fixture bytes for $name", [Text.Encoding]::ASCII) }
    $canonical = New-CanonicalReleaseChecksums -AssetDirectory $assets -AssetNames $names
    Assert-ChecksumContract (($canonical.Entries.Name -join ',') -ceq
        'patches-0.0.2.cdx.json,patches-0.0.2.mpp,release-receipt-0.0.2.json') 'Canonical assets are not ordered ordinally.'
    Assert-ChecksumContract ($canonical.Bytes[-1] -eq 10 -and $canonical.Bytes -notcontains 13) 'Canonical bytes do not use final LF.'
    $hash = 'a' * 64
    $invalidTexts = @(
        "$($hash.ToUpperInvariant())  asset.mpp`n", "$hash asset.mpp`n", "$hash *asset.mpp`n", "$hash  asset.mpp",
        "$hash  asset.mpp`r`n", "$hash  asset.mpp`n`n", "$hash  b.mpp`n$hash  a.mpp`n",
        "$hash  asset.mpp`n$hash  asset.mpp`n", "$hash  A.mpp`n$hash  a.mpp`n",
        "$hash  ../asset.mpp`n", "$hash  sub/asset.mpp`n", "$hash  sub\asset.mpp`n",
        "$hash  C:asset.mpp`n", "$hash  CON.mpp`n", "$hash  asset..mpp`n", "$hash  asset.mpp.`n"
    )
    foreach ($text in $invalidTexts) {
        Assert-ChecksumRefusal { ConvertFrom-CanonicalReleaseChecksums -Bytes ([Text.Encoding]::ASCII.GetBytes($text)) }
    }
    Assert-ChecksumRefusal { ConvertFrom-CanonicalReleaseChecksums -Bytes ([byte[]](239, 187, 191) + $canonical.Bytes) }
    Assert-ChecksumRefusal { ConvertFrom-CanonicalReleaseChecksums -Bytes ([byte[]]::new(65537)) } '*64 KiB*'
    $tooMany = ((0..128 | ForEach-Object { "$hash  $($_.ToString('D3')).mpp" }) -join "`n") + "`n"
    Assert-ChecksumRefusal { ConvertFrom-CanonicalReleaseChecksums -Bytes ([Text.Encoding]::ASCII.GetBytes($tooMany)) } '*128*'
    Assert-ChecksumRefusal { New-CanonicalReleaseChecksums -AssetDirectory $assets -AssetNames @($names[0], $names[0]) } '*duplicate*'
    Assert-ChecksumRefusal { Get-ReleaseChecksumFingerprint '0123456789ABCDEF' } '*complete*'

    $fingerprints = @()
    $publicKeys = @()
    foreach ($number in 0..1) {
        # Empty passphrases belong only to these short-lived test keys. Production pinentry is unchanged.
        $usage = if ($number -eq 0) { 'cert' } else { 'sign' }
        Invoke-ChecksumTestGpg $homes[$number] @('--pinentry-mode', 'loopback', '--passphrase=', '--quick-generate-key',
            "Checksum contract $number <checksum-$number@example.invalid>", 'ed25519', $usage, '1d') | Out-Null
        $listed = @(Invoke-ChecksumTestGpg $homes[$number] @('--with-colons', '--with-fingerprint', '--list-keys'))
        $fingerprint = (@($listed | Where-Object { $_ -like 'fpr:*' })[0].Split(':')[9]).ToUpperInvariant()
        $fingerprints += $fingerprint
        if ($number -eq 0) {
            Invoke-ChecksumTestGpg $homes[$number] @('--pinentry-mode', 'loopback', '--passphrase=', '--quick-add-key',
                $fingerprint, 'ed25519', 'sign', '1d') | Out-Null
        }
        $public = Join-Path $trusted "key-$number.asc"
        Invoke-ChecksumTestGpg $homes[$number] @('--armor', '--output', $public, '--export', $fingerprint) | Out-Null
        $publicKeys += $public
    }
    $signArguments = @{ AssetDirectory = $assets; AssetNames = $names; GpgHome = $homes[0];
        SigningFingerprint = $fingerprints[0]; TrustedPublicKeyPath = $publicKeys[0]; Gpg = $Gpg }
    Assert-ChecksumRefusal { Write-SignedReleaseChecksums @signArguments -GpgHome (Split-Path -Parent $PSScriptRoot) } '*outside*'
    Assert-ChecksumRefusal { Write-SignedReleaseChecksums @signArguments -GpgHome $assets } '*outside*'
    $emptyHome = Join-Path $work 'missing-private-key'
    [void][IO.Directory]::CreateDirectory($emptyHome)
    Assert-ChecksumRefusal { Write-SignedReleaseChecksums @signArguments -GpgHome $emptyHome } '*Provision*'
    Assert-ChecksumContract (-not (Test-Path -LiteralPath (Join-Path $assets 'SHA256SUMS.txt')) -and
        -not (Test-Path -LiteralPath (Join-Path $assets 'SHA256SUMS.txt.asc'))) 'Missing private key left signed output files.'
    $signed = & (Join-Path $PSScriptRoot 'sign-release-checksums.ps1') @signArguments
    $readArguments = @{ ChecksumsPath = $signed.ChecksumsPath; SignaturePath = $signed.SignaturePath;
        TrustedPublicKeyPath = $publicKeys[0]; TrustedFingerprint = $fingerprints[0]; Gpg = $Gpg }
    $authenticated = Read-AuthenticatedReleaseChecksums @readArguments
    Assert-ChecksumContract ($authenticated.Fingerprint -ceq $fingerprints[0] -and
        $authenticated.SignerFingerprint -cne $fingerprints[0]) 'A valid signing subkey was not bound to the pinned primary key.'
    $verified = & (Join-Path $PSScriptRoot 'verify-release-checksums.ps1') @readArguments `
        -AssetDirectory $assets -ExpectedAssetNames $names
    Assert-ChecksumContract ($verified.Authenticated -and $verified.VerifiedAssets -eq 3) 'The local verification CLI did not check every asset.'
    Assert-ChecksumRefusal { Assert-ReleaseChecksumAssets -Checksums $canonical -AssetDirectory $assets } '*Authenticate*'
    Assert-ChecksumRefusal { Assert-ReleaseChecksumAssets -Checksums $authenticated -AssetDirectory $assets -ExpectedAssetNames @($names[0]) } '*exactly*'

    $asset = Join-Path $assets $names[0]
    $original = [IO.File]::ReadAllBytes($asset)
    [IO.File]::WriteAllText($asset, 'altered asset', [Text.Encoding]::ASCII)
    Assert-ChecksumRefusal { Assert-ReleaseChecksumAssets -Checksums $authenticated -AssetDirectory $assets } '*mismatch*'
    [IO.File]::Delete($asset)
    Assert-ChecksumRefusal { Assert-ReleaseChecksumAssets -Checksums $authenticated -AssetDirectory $assets }
    [IO.File]::WriteAllBytes($asset, $original)

    $alteredSums = Join-Path $assets 'altered-sums.txt'
    $alteredText = [Text.Encoding]::ASCII.GetString($canonical.Bytes)
    $replacement = if ($alteredText[0] -eq '0') { '1' } else { '0' }
    $alteredText = $replacement + $alteredText.Substring(1)
    [IO.File]::WriteAllText($alteredSums, $alteredText, [Text.Encoding]::ASCII)
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -ChecksumsPath $alteredSums } '*authentication failed*'
    $noncanonical = Join-Path $assets 'noncanonical.txt'
    [IO.File]::WriteAllText($noncanonical, [Text.Encoding]::ASCII.GetString($canonical.Bytes).Replace("`n", "`r`n"), [Text.Encoding]::ASCII)
    $noncanonicalSignature = "$noncanonical.asc"
    Write-ChecksumTestSignature $homes[0] $fingerprints[0] $noncanonical $noncanonicalSignature
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -ChecksumsPath $noncanonical -SignaturePath $noncanonicalSignature } '*LF*'
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -SignaturePath (Join-Path $assets 'missing.asc') }

    $wrongSignature = Join-Path $assets 'wrong-key.asc'
    Write-ChecksumTestSignature $homes[1] $fingerprints[1] $signed.ChecksumsPath $wrongSignature
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -SignaturePath $wrongSignature } '*authentication failed*'
    $primarySigned = Read-AuthenticatedReleaseChecksums @readArguments -SignaturePath $wrongSignature `
        -TrustedPublicKeyPath $publicKeys[1] -TrustedFingerprint $fingerprints[1]
    Assert-ChecksumContract ($primarySigned.SignerFingerprint -ceq $fingerprints[1]) 'A valid pinned primary signing key was refused.'
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -TrustedPublicKeyPath $publicKeys[1] } '*pinned fingerprint*'
    $badSignature = Join-Path $assets 'damaged-signature.asc'
    $damaged = [IO.File]::ReadAllBytes($signed.SignaturePath)
    $damaged[100] = $damaged[100] -bxor 1
    [IO.File]::WriteAllBytes($badSignature, $damaged)
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -SignaturePath $badSignature } '*authentication failed*'
    $extraKeys = Join-Path $trusted 'extra-keys.asc'
    [IO.File]::WriteAllBytes($extraKeys, ([IO.File]::ReadAllBytes($publicKeys[0]) + [IO.File]::ReadAllBytes($publicKeys[1])))
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -TrustedPublicKeyPath $extraKeys } '*extra keys*'
    $extraSignature = Join-Path $assets 'extra-signatures.asc'
    [IO.File]::WriteAllBytes($extraSignature, ([IO.File]::ReadAllBytes($signed.SignaturePath) + [IO.File]::ReadAllBytes($signed.SignaturePath)))
    Assert-ChecksumRefusal { Read-AuthenticatedReleaseChecksums @readArguments -SignaturePath $extraSignature } '*authentication failed*'

    $sumHash = Get-ReleaseChecksumHash $signed.ChecksumsPath
    $sigHash = Get-ReleaseChecksumHash $signed.SignaturePath
    Assert-ChecksumRefusal { Write-SignedReleaseChecksums @signArguments }
    Assert-ChecksumContract ((Get-ReleaseChecksumHash $signed.ChecksumsPath) -ceq $sumHash -and
        (Get-ReleaseChecksumHash $signed.SignaturePath) -ceq $sigHash) 'An output collision changed signed files.'
    $partial = Join-Path $assets 'partial-sums.txt'
    Assert-ChecksumRefusal { Write-SignedReleaseChecksums @signArguments -ChecksumsPath $partial -SignaturePath $signed.SignaturePath }
    Assert-ChecksumContract (-not (Test-Path -LiteralPath $partial) -and
        (Get-ReleaseChecksumHash $signed.SignaturePath) -ceq $sigHash) 'A partial output reservation leaked or deleted another signature.'
    $commaSums = Join-Path $assets 'comma-sums.txt'
    $commaSigned = & (Join-Path $PSScriptRoot 'sign-release-checksums.ps1') @signArguments -AssetNames ($names -join ',') -ChecksumsPath $commaSums
    $commaVerified = & (Join-Path $PSScriptRoot 'verify-release-checksums.ps1') @readArguments `
        -ChecksumsPath $commaSigned.ChecksumsPath -SignaturePath $commaSigned.SignaturePath -AssetDirectory $assets `
        -ExpectedAssetNames ($names -join ',')
    Assert-ChecksumContract ($commaVerified.VerifiedAssets -eq 3) 'Comma-separated CLI asset lists were not normalized.'
    Write-Host '[checksums] canonical bytes, pinned primary/subkey signatures, tamper and collision contracts passed'
    $global:LASTEXITCODE = 0
} finally {
    $gpgconf = Join-Path (Split-Path -Parent (Get-Command $Gpg -ErrorAction Stop).Source) 'gpgconf.exe'
    foreach ($keyHome in @($homes) + @((Join-Path $work 'missing-private-key'))) {
        if (Test-Path -LiteralPath $gpgconf) { Invoke-ReleaseChecksumGpg $gpgconf @('--homedir', $keyHome, '--kill', 'gpg-agent') | Out-Null }
    }
    $absolute = [IO.Path]::GetFullPath($work)
    if (-not $absolute.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test keyring cleanup.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force -ErrorAction Stop
}
