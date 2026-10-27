param(
    [switch]$ExecuteRecovery,
    [Parameter(Mandatory=$true)][ValidatePattern('^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?):[0-9]{1,5}$')][string]$Target,
    [Parameter(Mandatory=$true)][ValidateSet('mdarcy','sif')][string]$ExpectedDevice,
    [Parameter(Mandatory=$true)][ValidateSet(0,1)][int]$DesiredStockEnabledSetting,
    [string]$AdbPath = $env:LM_ADB_PATH
)
$ErrorActionPreference = 'Stop'
if (!$ExecuteRecovery) { throw 'Recovery only: explicit -ExecuteRecovery required.' }
# Desired state is explicitly approved, never inferred from model or present state.
# Stock HOME cannot be restored while Stock remains disabled (states 2/3/4).
if ([string]::IsNullOrWhiteSpace($AdbPath)) { $AdbPath = (Get-Command adb.exe -ErrorAction Stop).Source }
if (!(Test-Path -LiteralPath $AdbPath -PathType Leaf)) { throw 'ADB executable not found. Set LM_ADB_PATH or pass -AdbPath.' }
$adb = $AdbPath
function ReadAdb([string[]]$Arguments) {
    $result = @(& $adb -s $Target @Arguments)
    if ($LASTEXITCODE -ne 0) { throw 'ADB command failed; stop and review.' }
    return ($result -join "`n").Trim()
}
if ((ReadAdb @('get-state')) -ne 'device') { throw 'Target not connected/authorized' }
if (!(ReadAdb @('shell','getprop','ro.product.manufacturer')).Equals('NVIDIA',[StringComparison]::OrdinalIgnoreCase) -or
    (ReadAdb @('shell','getprop','ro.product.device')) -cne $ExpectedDevice -or
    (ReadAdb @('shell','getprop','ro.build.version.sdk')) -ne '30' -or
    (ReadAdb @('shell','am','get-current-user')) -ne '0' -or
    (ReadAdb @('shell','id','-u')) -ne '2000') { throw 'Target identity/API/user/shell mismatch' }
if ((ReadAdb @('shell','pm','path','com.google.android.tvlauncher')) -notmatch '^package:') { throw 'Stock package missing' }
$verb = if ($DesiredStockEnabledSetting -eq 0) { 'default-state' } else { 'enable' }
ReadAdb @('shell','pm',$verb,'--user','0','com.google.android.tvlauncher')
ReadAdb @('shell','cmd','role','add-role-holder','--user','0','android.app.role.HOME','com.google.android.tvlauncher')
ReadAdb @('shell','cmd','package','set-home-activity','--user','0','com.google.android.tvlauncher/.MainActivity')
$resolved = ReadAdb @('shell','cmd','package','resolve-activity','--brief','--user','0','-a','android.intent.action.MAIN','-c','android.intent.category.HOME')
if (($resolved -split "`n") -notcontains 'com.google.android.tvlauncher/.MainActivity') { throw 'Stock HOME restoration not verified' }
$dump = ReadAdb @('shell','dumpsys','package','com.google.android.tvlauncher')
$state = ($dump -split "`n" | Where-Object { $_ -match '^\s*User 0: ceDataInode=' } | Select-Object -First 1)
if (!$state -or $state -notmatch '\binstalled=true\b' -or $state -notmatch "\benabled=$DesiredStockEnabledSetting\b") { throw 'Exact Stock enabled state not verified' }
ReadAdb @('shell','am','start','-n','com.google.android.tvlauncher/.MainActivity')
Write-Output "Stock HOME and explicit enabled setting $DesiredStockEnabledSetting verified. Confirm physical HOME on the TV."
