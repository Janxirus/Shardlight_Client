# Replaces the banner and the welcome picture inside a Windows installer (.msi) with Shardlight's own.
#
#   pwsh tools/release/set_msi_images.ps1 -Msi "Shardlight Client-0.1.0.msi" -Banner packaging/windows/banner.bmp -Dialog packaging/windows/dialog.bmp
#
# jpackage has no option for these two pictures. The installer keeps them as rows in its Binary table
# (WixUI_Bmp_Banner and WixUI_Bmp_Dialog), so this updates those rows through the Windows Installer COM object.
# It never fails the build: if the rows are missing the installer simply keeps jpackage's default look, and
# the script says so. Sign the installer AFTER running this, because changing it removes a signature.
param(
    [Parameter(Mandatory = $true)][string]$Msi,
    [Parameter(Mandatory = $true)][string]$Banner,
    [Parameter(Mandatory = $true)][string]$Dialog
)
$ErrorActionPreference = 'Stop'

function Invoke-Com($obj, $method, $arguments) {
    $obj.GetType().InvokeMember($method, [Reflection.BindingFlags]::InvokeMethod, $null, $obj, $arguments)
}

try {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $db = Invoke-Com $installer 'OpenDatabase' @((Resolve-Path $Msi).Path, 1)   # 1 = open for writing
    $changed = 0
    foreach ($pair in @(@('WixUI_Bmp_Banner', $Banner), @('WixUI_Bmp_Dialog', $Dialog))) {
        $name, $file = $pair
        $sql = 'SELECT `Name`, `Data` FROM `Binary` WHERE `Name`=''{0}''' -f $name
        $view = Invoke-Com $db 'OpenView' @($sql)
        Invoke-Com $view 'Execute' @($null) | Out-Null
        $record = Invoke-Com $view 'Fetch' @()
        if ($null -eq $record) {
            Write-Host "::warning::The installer has no '$name' picture; it keeps the default look."
            continue
        }
        Invoke-Com $record 'SetStream' @(2, (Resolve-Path $file).Path) | Out-Null
        Invoke-Com $view 'Modify' @(2, $record) | Out-Null   # 2 = update
        $changed++
        Invoke-Com $view 'Close' @() | Out-Null
    }
    Invoke-Com $db 'Commit' @() | Out-Null
    Write-Host "Installer pictures replaced: $changed of 2."
} catch {
    Write-Host "::warning::Could not change the installer pictures ($($_.Exception.Message)); the installer keeps the default look."
}
exit 0
