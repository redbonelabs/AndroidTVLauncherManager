param([string]$BuildId)
$ErrorActionPreference = 'Stop'
if ($BuildId -notmatch '^[A-Za-z0-9-]+$') { throw 'Invalid build ID.' }
$projectRoot = $PSScriptRoot
$output = Join-Path $projectRoot "out\$BuildId"
$artifact = Get-Content -LiteralPath "$output\artifact.json" -Raw | ConvertFrom-Json
$checks = [Collections.Generic.List[string]]::new()
function Assert-Check([bool]$condition, [string]$name) { if (-not $condition) { throw "FAIL: $name" }; $checks.Add($name) }
Assert-Check ((Get-FileHash -LiteralPath $artifact.Apk -Algorithm SHA256).Hash -eq $artifact.SHA256) 'APK matches recorded SHA-256'
[xml]$manifest = Get-Content -LiteralPath "$projectRoot\app\src\main\AndroidManifest.xml" -Raw
$ns = [Xml.XmlNamespaceManager]::new($manifest.NameTable)
$ns.AddNamespace('a','http://schemas.android.com/apk/res/android')
Assert-Check ($manifest.SelectNodes('/manifest/uses-permission').Count -eq 1 -and $manifest.SelectNodes('/manifest/uses-permission[@a:name="android.permission.INTERNET"]',$ns).Count -eq 1) 'Only INTERNET permission; required for outgoing local ADB'
Assert-Check ($manifest.SelectNodes('/manifest/application/activity').Count -eq 1 -and $manifest.SelectNodes('/manifest/application/activity[@a:exported="true" and @a:launchMode="singleTask"]',$ns).Count -eq 1) 'One single-task TV entry activity; no command IPC'
Assert-Check ($manifest.SelectNodes('/manifest/application/activity/intent-filter/category[@a:name="android.intent.category.HOME"]',$ns).Count -eq 0) 'Manager is not a HOME replacement'
Assert-Check ($manifest.SelectNodes('/manifest/application/service | /manifest/application/receiver | /manifest/application/activity-alias | /manifest/application/provider').Count -eq 0) 'No background service, receiver, provider, alias or helper publication'
Assert-Check ($manifest.SelectNodes('/manifest/application[@a:allowBackup="false"]',$ns).Count -eq 1) 'Application backup disabled'
Assert-Check ($manifest.SelectNodes('/manifest/queries/intent[category[@a:name="android.intent.category.HOME"]]',$ns).Count -eq 1 -and $manifest.SelectNodes('/manifest/queries/package').Count -eq 2) 'Scoped HOME discovery preserved'
$compiled = Get-Content -LiteralPath "$output\apk-manifest.txt" -Raw
Assert-Check (([regex]::Matches($compiled,'E: uses-permission ')).Count -eq 1 -and $compiled.Contains('android.permission.INTERNET')) 'Compiled permission surface matches source'
Assert-Check ($compiled -notmatch 'E: (service|receiver|activity-alias|provider) ') 'Compiled APK has no helper, service, receiver or alias'
Assert-Check (($compiled -split 'E: application ')[1] -notmatch 'android.intent.category.HOME') 'Compiled activity is not a HOME handler'
Assert-Check ($compiled -match 'minSdkVersion.*0x1e' -and $compiled -match 'targetSdkVersion.*0x22') 'Compiled minimum API 30 and target 34'
$badging = Get-Content -LiteralPath "$output\apk-badging.txt" -Raw
$config = Get-Content -LiteralPath "$projectRoot\build-config.json" -Raw | ConvertFrom-Json
Assert-Check ($badging.Contains("package: name='$($config.package)' versionCode='$($config.versionCode)' versionName='$($config.versionName)'")) 'Package/version match build configuration'
Assert-Check ($badging.Contains("leanback-launchable-activity: name='com.shieldtools.launchermanager.MainActivity'")) 'Native TV app entry remains available'
Assert-Check ((Get-Content "$output\signature-verification.txt" -Raw).Contains('Signer #1 certificate SHA-256 digest: efde12513ce1b08e0ea49477037eb214fe91a39755a451e3f739849e5723455b')) 'Signer continuity with installed 0.3.0-dev'
foreach ($suite in @(@('safety-test-results.txt','26 safety tests passed'),@('discovery-test-results.txt','32 discovery tests passed'),@('backend-test-results.txt','60 backend/security simulation tests passed'),@('on-device-command-tests.txt','12 on-device command contract tests passed'),@('native-adb-tests.txt','52 native ADB protocol/security tests passed'),@('recovery-dialog-tests.txt','17 recovery dialog source-contract tests passed'),@('foreground-tests.txt','14 foreground capture/regression tests passed'),@('connection-state-tests.txt','12 connection-state tests passed'),@('shield-device-tests.txt','8 Shield device policy tests passed'),@('recovery-script-tests.txt','14 recovery script tests passed'))) { Assert-Check ((Get-Content "$output\$($suite[0])" -Raw).Contains($suite[1])) $suite[1] }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($artifact.Apk)
try {
    $names = @($zip.Entries.FullName)
    Assert-Check (@($names | Where-Object { $_ -match '^(lib/|assets/)|\.(apk|p12|jks|pem|key|ps1|sh)$|adbkey' }).Count -eq 0) 'No native payloads, assets, scripts or embedded key files'
    Assert-Check (@($names | Where-Object { $_ -match '^classes.*\.dex$' }).Count -eq 1) 'Single application DEX'
    $stream=$zip.GetEntry('classes.dex').Open(); $memory=[IO.MemoryStream]::new()
    try { $stream.CopyTo($memory); $dex=$memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
} finally { $zip.Dispose() }
Assert-Check ([Text.Encoding]::ASCII.GetString($dex,0,8) -eq "dex`n039`0") 'Android 11 compatible DEX 039'
$count=[BitConverter]::ToUInt32($dex,56); $offset=[BitConverter]::ToUInt32($dex,60)
$strings=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
for($index=0; $index -lt $count; $index++) {
    $position=[int][BitConverter]::ToUInt32($dex,$offset+4*$index)
    do { $next=$dex[$position]; $position++ } while(($next -band 128) -ne 0)
    $end=$position; while($dex[$end] -ne 0){$end++}
    [void]$strings.Add([Text.Encoding]::UTF8.GetString($dex,$position,$end-$position))
}
foreach($forbidden in @('Ljava/lang/ProcessBuilder;','Ljava/net/ServerSocket;','Ljava/net/DatagramSocket;','setApplicationEnabledSetting','setComponentEnabledSetting','clearApplicationUserData','installPackage','deletePackage','grantRuntimePermission','adoptShellPermissionIdentity')) { Assert-Check (-not $strings.Contains($forbidden)) "No unrelated privileged/process/listening API: $forbidden" }
foreach($required in @('Ljava/net/Socket;','127.0.0.1','AndroidKeyStore','AES/GCM/NoPadding','Lcom/shieldtools/launchermanager/NativeSwitchSession;','Lcom/shieldtools/launchermanager/backend/AdbProtocol;','Lcom/shieldtools/launchermanager/backend/ShieldDevicePolicy;','mdarcy','sif')) { Assert-Check ($strings.Contains($required)) "Expected native ADB/identity reference: $required" }
Assert-Check (-not ($strings | Where-Object { $_ -match '192\.168\.|BEGIN (RSA |OPENSSH )?PRIVATE KEY' })) 'No LAN targets or literal private keys in DEX'
$sourceFiles=@(Get-ChildItem "$projectRoot\app\src\main\java","$projectRoot\shared\src" -Filter '*.kt' -Recurse -File)
$source=($sourceFiles | ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"
Assert-Check ($source -notmatch 'Runtime\.getRuntime|ProcessBuilder|Settings\.(Global|Secure|System)\.put|ServerSocket|DatagramSocket|System\.loadLibrary') 'No local OS executor, settings mutation, listeners, discovery datagrams or native loader'
Assert-Check ($source -notmatch 'fun\s+(execute|command|shell)\([^)]*(command|text|args):\s*String') 'No generic string command entry point'
Assert-Check ((Get-Item "$output\dex-build.log").Length -eq 0) 'D8 diagnostics empty'
$sources=@(Get-ChildItem "$projectRoot\app\src","$projectRoot\shared\src","$projectRoot\tests" -Recurse -File)
$sources+=Get-Item "$projectRoot\Build-Local.ps1","$projectRoot\Verify-LocalArtifact.ps1","$projectRoot\build-config.json","$projectRoot\app\build.gradle.kts"
$sources | ForEach-Object { [pscustomobject]@{Path=$_.FullName;SHA256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} } | Export-Csv "$output\source-hashes.csv" -NoTypeInformation
[pscustomobject]@{BuildId=$BuildId;ApkSHA256=$artifact.SHA256;ChecksPassed=$checks.Count;Checks=$checks;DeviceUsed=$false;Limitations='Automated artifact/JVM/mock-script checks only; prior Pro and primary Tube hardware results are documented in README. This build does not install or test the release APK on hardware.'} | ConvertTo-Json -Depth 4 | Set-Content "$output\static-verification.json"
Write-Output "$($checks.Count) artifact/static checks passed; 216 JVM tests and 17 recovery dialog checks and 14 mock recovery checks passed. No device commands executed."








