# Signs every .msi in a folder with the code-signing certificate from the environment, then checks the signature.
#
#   CERT (base64 of the .pfx file), CERT_PASSWORD, optional TIMESTAMP_URL are read from the environment.
#   pwsh tools/release/sign_windows.ps1 -Folder launcher-app/build/jpackage/installer
#
# CI runs this only when the certificate secrets exist (docs/RELEASING.md). The certificate is written to a
# temporary file that is deleted at the end; nothing is printed that could reveal it.
param([Parameter(Mandatory = $true)][string]$Folder)
$ErrorActionPreference = 'Stop'

$signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $signtool) { throw 'signtool.exe was not found on this machine' }
$timestamp = if ($env:TIMESTAMP_URL) { $env:TIMESTAMP_URL } else { 'http://timestamp.digicert.com' }

$pfx = Join-Path $env:RUNNER_TEMP 'codesign.pfx'
[IO.File]::WriteAllBytes($pfx, [Convert]::FromBase64String($env:CERT))
try {
    foreach ($file in Get-ChildItem $Folder -Filter *.msi) {
        & $signtool.FullName sign /fd SHA256 /td SHA256 /tr $timestamp /f $pfx /p $env:CERT_PASSWORD /d 'Shardlight Client' $file.FullName
        if ($LASTEXITCODE -ne 0) { throw "signing $($file.Name) failed" }
        & $signtool.FullName verify /pa $file.FullName
        if ($LASTEXITCODE -ne 0) { throw "the signature of $($file.Name) did not verify" }
        Write-Host "Signed $($file.Name)"
    }
    "Windows signing: **signed**" >> $env:GITHUB_STEP_SUMMARY
} finally {
    Remove-Item $pfx -Force -ErrorAction SilentlyContinue
}
