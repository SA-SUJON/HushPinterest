<# Exercise account-preserving leases and installs without a real device. #>
[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
function Assert-Safety([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Refusal([scriptblock]$Action, [string]$Pattern) {
    try { & $Action; throw 'Expected refusal did not happen.' }
    catch { if ($_.Exception.Message -notlike $Pattern) { throw } }
}
$work = Join-Path ([IO.Path]::GetTempPath()) ('hushpinterest-safety-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($work)
$apk = Join-Path $work 'new.apk'
[IO.File]::WriteAllBytes($apk, [byte[]](1, 2, 3))
function New-SafetyAdb([bool]$Installed = $true, [string]$IdentitySerial = 'emulator-5998') {
    $state = [pscustomobject]@{ Calls = [Collections.Generic.List[string]]::new(); Marker = ''; Size = 3 }
    $invoke = {
        param($Executable, [string[]]$Arguments)
        $line = $Arguments -join ' '
        $state.Calls.Add($line)
        $output = switch -Wildcard ($line) {
            '* get-state' { 'device' }
            '* get-serialno' { $IdentitySerial }
            '* emu avd name' { 'Fixture_API36'; 'OK' }
            '* shell getprop ro.product.model' { 'sdk_gphone64_x86_64' }
            '* shell getprop ro.build.fingerprint' { 'google/sdk/test' }
            '* shell getprop ro.build.version.sdk' { '36' }
            '* shell getprop ro.product.cpu.abi' { 'x86_64' }
            '* shell pm path com.pinterest' { if ($Installed) { 'package:/data/app/base.apk' } }
            '* pull *' { [IO.File]::WriteAllBytes($Arguments[4], [byte[]](1, 2, 3)); 'pulled' }
            '* install *' { 'Success' }
            '* push *' { 'pushed' }
            '* shell log -p *' { $state.Marker = ($Arguments[3] -split ' ')[-1] }
            '* shell dex2oat64*' { 'exit=0'; 'size=3' }
            '* logcat -d' { "W HushPinterestVerify: $($state.Marker)" }
            '* shell rm -rf *' { }
            default { throw "Unexpected ADB operation: $line" }
        }
        [pscustomobject]@{ ExitCode = 0; Output = @($output) }
    }.GetNewClosure()
    [pscustomobject]@{ State = $state; Invoker = $invoke }
}
try {
    $fake = New-SafetyAdb
    $lease = Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -AdbInvoker $fake.Invoker
    Assert-Safety $lease.Owned 'A newly created lease is not helper-owned.'
    $saved = Get-Content -LiteralPath $lease.Path -Raw | ConvertFrom-Json
    Assert-Safety ($saved.serial -eq 'emulator-5998' -and $saved.project -eq 'HushPinterest' -and
        $saved.chatIdentity -eq 'safety-test' -and $saved.ownershipToken -and
        [DateTimeOffset]::Parse($saved.expiresUtc) -gt [DateTimeOffset]::UtcNow) 'Incomplete lease identity.'
    $beforeBusy = $fake.State.Calls.Count
    Assert-Refusal { Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project other `
        -ChatIdentity other -LeaseDirectory $work -AdbInvoker $fake.Invoker } '*leased*'
    Assert-Safety ($fake.State.Calls.Count -eq $beforeBusy) 'Busy lease ran ADB.'
    $borrowed = Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -LeaseToken $lease.Token -AdbInvoker $fake.Invoker
    Assert-Safety (-not $borrowed.Owned) 'A caller lease became helper-owned.'
    Exit-HushDeviceLease $borrowed
    Assert-Safety (Test-Path -LiteralPath $lease.Path) 'A borrowed lease was released.'
    $saved | Add-Member -NotePropertyName expectedIdentity -NotePropertyValue other-avd -Force
    $saved.expiresUtc = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    $saved | ConvertTo-Json | Set-Content -LiteralPath $lease.Path
    Assert-Refusal { Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -LeaseToken $lease.Token -AdbInvoker $fake.Invoker } '*identity*'
    Assert-Safety (Test-Path -LiteralPath $lease.Path) 'Wrong profile released the caller lease.'
    $saved.PSObject.Properties.Remove('expectedIdentity')
    $saved | ConvertTo-Json | Set-Content -LiteralPath $lease.Path
    Renew-HushDeviceLease $lease
    $afterRenew = Get-Content -LiteralPath $lease.Path -Raw | ConvertFrom-Json
    Assert-Safety ($afterRenew.ownershipToken -eq $saved.ownershipToken) 'Renew replaced ownership.'
    Exit-HushDeviceLease $lease
    Assert-Safety (-not (Test-Path -LiteralPath $lease.Path)) 'Owned lease was not released.'

    $saved.expiresUtc = [DateTimeOffset]::UtcNow.AddMinutes(-2).ToString('o')
    $saved | ConvertTo-Json | Set-Content -LiteralPath $lease.Path
    $oldBytes = [IO.File]::ReadAllText($lease.Path)
    Assert-Refusal { Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -AdbInvoker $fake.Invoker } '*expired*'
    Assert-Safety ([IO.File]::ReadAllText($lease.Path) -eq $oldBytes) 'An expired lease was overwritten.'
    Remove-Item -LiteralPath $lease.Path
    $wrongIdentity = New-SafetyAdb -IdentitySerial other
    Assert-Refusal { Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -AdbInvoker $wrongIdentity.Invoker } '*identity*'
    Assert-Safety (-not (Test-Path -LiteralPath $lease.Path)) 'Identity failure leaked an owned lease.'

    foreach ($case in @('same', 'fresh', 'wrong-signer', 'downgrade', 'missing-signer', 'wrong-package')) {
        $fake = New-SafetyAdb -Installed ($case -ne 'fresh')
        $lease = Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
            -ChatIdentity safety-test -LeaseDirectory $work -AdbInvoker $fake.Invoker
        $manifest = {
            param($Path, $Aapt2)
            $installed = $Path -like '*installed*'
            [pscustomobject]@{ package = if ($case -eq 'wrong-package') { 'other.package' } else { 'com.pinterest' };
                versionCode = if ($installed -and $case -eq 'downgrade') { '200' } else { '100' } }
        }.GetNewClosure()
        $signers = {
            param($Path, $Aapt2)
            if ($case -eq 'missing-signer') { return }
            if ($Path -like '*installed*' -and $case -eq 'wrong-signer') { return 'b' * 64 }
            'a' * 64
        }.GetNewClosure()
        $install = { Install-HushAndroidApk -Adb fake -Serial emulator-5998 -Apk $apk `
            -PackageName com.pinterest -Aapt2 fake -Lease $lease -AdbInvoker $fake.Invoker `
            -ManifestReader $manifest -SignerReader $signers }
        try {
            if ($case -in @('same', 'fresh')) { & $install }
            else { Assert-Refusal $install '*refused*' }
            $installs = @($fake.State.Calls | Where-Object { $_ -like '* install *' })
            Assert-Safety ($installs.Count -eq [int]($case -in @('same', 'fresh'))) "$case installation count."
            Assert-Safety (-not ($fake.State.Calls -match 'uninstall|clear|install .* -g|install .* -d')) 'Account or permissions were modified.'
        } finally { Exit-HushDeviceLease $lease }
    }

    $fake = New-SafetyAdb
    $lease = Enter-HushDeviceLease -Adb fake -Serial emulator-5998 -Project HushPinterest `
        -ChatIdentity safety-test -LeaseDirectory $work -AdbInvoker $fake.Invoker
    $beforeBusy = $fake.State.Calls.Count
    Assert-Refusal { Invoke-AndroidVerifierTally -Adb fake -Serial emulator-5998 -Local $apk `
        -Label safety -LeaseDirectory $work -AdbInvoker $fake.Invoker } '*leased*'
    Assert-Safety ($fake.State.Calls.Count -eq $beforeBusy) 'Unleased verifier mutated a busy device.'
    [void](Invoke-AndroidVerifierTally -Adb fake -Serial emulator-5998 -Local $apk -Label safety `
        -LeaseDirectory $work -Project HushPinterest -ChatIdentity safety-test -LeaseToken $lease.Token `
        -AdbInvoker $fake.Invoker)
    Assert-Safety (Test-Path -LiteralPath $lease.Path) 'Verifier released caller-owned lease.'
    Exit-HushDeviceLease $lease
    $global:LASTEXITCODE = 0
    Write-Host '[device] lease, signer, downgrade and mutation refusal contracts passed'
} finally {
    $absolute = [IO.Path]::GetFullPath($work)
    if (-not $absolute.StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup.' }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
