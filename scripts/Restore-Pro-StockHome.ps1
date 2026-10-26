param([switch]$ExecuteRecovery, [Parameter(Mandatory=$true)][ValidatePattern('^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?):[0-9]{1,5}$')][string]$Target, [string]$AdbPath = $env:LM_ADB_PATH)
$ErrorActionPreference = 'Stop'
if (!$ExecuteRecovery) { throw 'Recovery only: explicit -ExecuteRecovery required. Do not run during routine testing.' }
if ([string]::IsNullOrWhiteSpace($AdbPath)) {
    $AdbPath = (Get-Command adb.exe -ErrorAction Stop).Source
}
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB executable not found. Set LM_ADB_PATH or pass -AdbPath.' }
$adb = $AdbPath
$target=$Target
if ((& $adb -s $target get-state) -ne 'device') { throw 'Target not connected/authorized' }
if ((& $adb -s $target shell getprop ro.product.manufacturer).Trim() -ne 'NVIDIA' -or
    (& $adb -s $target shell getprop ro.product.device).Trim() -ne 'mdarcy') { throw 'Target identity mismatch' }
function RestoreCommand([string[]]$Arguments) {
    & $adb -s $target shell @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Recovery command failed; stop and review.' }
}
RestoreCommand @('pm','default-state','--user','0','com.google.android.tvlauncher')
RestoreCommand @('cmd','role','add-role-holder','--user','0','android.app.role.HOME','com.google.android.tvlauncher')
RestoreCommand @('cmd','package','set-home-activity','--user','0','com.google.android.tvlauncher/.MainActivity')
$resolved=@(& $adb -s $target shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME)
if ($LASTEXITCODE -ne 0 -or $resolved -notcontains 'com.google.android.tvlauncher/.MainActivity') { throw 'Stock HOME restoration not verified' }
RestoreCommand @('am','start','-n','com.google.android.tvlauncher/.MainActivity')
Write-Output 'Stock HOME resolves correctly. Confirm physical HOME and Settings access on the TV.'


