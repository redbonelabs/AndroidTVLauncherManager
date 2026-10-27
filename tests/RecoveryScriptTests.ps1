param([string]$ProjectRoot=(Split-Path $PSScriptRoot -Parent), [Parameter(Mandatory=$true)][string]$OutputDirectory)
$ErrorActionPreference='Stop'
$script=Join-Path $ProjectRoot 'scripts\Restore-Pro-StockHome.ps1'
$fake=Join-Path $OutputDirectory 'fake-recovery-adb.ps1'
$log=Join-Path $OutputDirectory 'fake-recovery-commands.txt'
$names=@('LM_RT_LOG','LM_RT_DEVICE','LM_RT_MANUFACTURER','LM_RT_API','LM_RT_USER','LM_RT_UID','LM_RT_STATE','LM_RT_FAIL','LM_RT_BADSTATE','LM_RT_BADHOME')
$saved=@{};foreach($n in $names){$saved[$n]=[Environment]::GetEnvironmentVariable($n)}
@'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Parts)
$global:LASTEXITCODE=0
$text=$Parts -join ' '
Add-Content -LiteralPath $env:LM_RT_LOG -Value $text
if($env:LM_RT_FAIL -eq 'yes'){$global:LASTEXITCODE=1;return}
switch -Regex ($text) {
 ' get-state$' { 'device';return }
 ' ro.product.manufacturer$' {$env:LM_RT_MANUFACTURER;return}
 ' ro.product.device$' {$env:LM_RT_DEVICE;return}
 ' ro.build.version.sdk$' {$env:LM_RT_API;return}
 ' am get-current-user$' {$env:LM_RT_USER;return}
 ' id -u$' {$env:LM_RT_UID;return}
 ' pm path ' {'package:/system/fake.apk';return}
 ' pm default-state ' {$env:LM_RT_STATE='0';'default';return}
 ' pm enable ' {$env:LM_RT_STATE='1';'enabled';return}
 ' resolve-activity ' {if($env:LM_RT_BADHOME -eq 'yes'){'org.other/.Home'}else{'com.google.android.tvlauncher/.MainActivity'};return}
 ' dumpsys package ' {$state=if($env:LM_RT_BADSTATE -eq 'yes'){'3'}else{$env:LM_RT_STATE};"User 0: ceDataInode=123 installed=true enabled=$state";return}
 default {'Success'}
}
'@ | Set-Content $fake -Encoding UTF8
$script:passed=0
function Reset-Mock {
 Set-Content $log ''
 $env:LM_RT_LOG=$log;$env:LM_RT_DEVICE='mdarcy';$env:LM_RT_MANUFACTURER='NVIDIA';$env:LM_RT_API='30';$env:LM_RT_USER='0';$env:LM_RT_UID='2000';$env:LM_RT_STATE='3';$env:LM_RT_FAIL='';$env:LM_RT_BADSTATE='';$env:LM_RT_BADHOME=''
}
function Run-Recovery([string]$Device='mdarcy',[int]$Setting=0,[bool]$Execute=$true){& $script -ExecuteRecovery:$Execute -Target 'synthetic-shield:5555' -ExpectedDevice $Device -DesiredStockEnabledSetting $Setting -AdbPath $fake | Out-Null}
function Denied([scriptblock]$Body){$failed=$false;try{& $Body}catch{$failed=$true};if(!$failed){throw 'Expected recovery rejection'}}
function Assert-NoWrites {if((Get-Content $log -Raw) -match ' pm (default-state|enable) | add-role-holder | set-home-activity '){throw 'Unexpected mocked mutation'}}
function Test-Recovery([string]$Name,[scriptblock]$Body){Reset-Mock;& $Body;$script:passed++;Write-Output "PASS $Name"}
try {
 foreach($device in @('mdarcy','sif')){foreach($state in @(0,1)){
  Test-Recovery "$device restores explicitly requested state $state" {
   $env:LM_RT_DEVICE=$device;Run-Recovery $device $state
   $verb=if($state -eq 0){'default-state'}else{'enable'}
   $text=Get-Content $log -Raw
   if($text -notmatch "pm $verb --user 0 com.google.android.tvlauncher" -or $env:LM_RT_STATE -ne "$state"){throw 'Wrong state command'}
   if($text -match 'disable|uninstall|clear |reboot|youtube|tvplayer'){throw 'Unrelated command'}
  }
 }}
 Test-Recovery 'wrong explicitly selected device rejected before writes' {Denied {Run-Recovery 'sif'};Assert-NoWrites}
 Test-Recovery 'wrong manufacturer rejected' {$env:LM_RT_MANUFACTURER='Other';Denied {Run-Recovery};Assert-NoWrites}
 Test-Recovery 'API outside 30 rejected' {$env:LM_RT_API='31';Denied {Run-Recovery};Assert-NoWrites}
 Test-Recovery 'non-owner Android user rejected' {$env:LM_RT_USER='10';Denied {Run-Recovery};Assert-NoWrites}
 Test-Recovery 'root or non-shell UID rejected' {$env:LM_RT_UID='0';Denied {Run-Recovery};Assert-NoWrites}
 Test-Recovery 'disabled desired state rejected rather than hidden baseline normalization' {Denied {Run-Recovery 'mdarcy' 3};Assert-NoWrites}
 Test-Recovery 'explicit execute guard causes zero ADB calls' {Denied {Run-Recovery 'mdarcy' 0 $false};if((Get-Content $log -Raw).Trim()){throw 'ADB called without execution authorization'}}
 Test-Recovery 'read command error stops before writes' {$env:LM_RT_FAIL='yes';Denied {Run-Recovery};Assert-NoWrites}
 Test-Recovery 'wrong actual HOME is a failure' {$env:LM_RT_BADHOME='yes';Denied {Run-Recovery}}
 Test-Recovery 'wrong exact enabled setting is a failure' {$env:LM_RT_BADSTATE='yes';Denied {Run-Recovery}}
 Write-Output "$script:passed recovery script tests passed (mock ADB only; no device/network)"
} finally {
 foreach($n in $names){[Environment]::SetEnvironmentVariable($n,$saved[$n])}
 foreach($p in @($fake,$log)){if(Test-Path -LiteralPath $p){Remove-Item -LiteralPath $p -Force}}
}
