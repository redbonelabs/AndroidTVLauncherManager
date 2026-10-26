param([string]$BuildId = (Get-Date -Format 'yyyyMMdd-HHmmss'))
$ErrorActionPreference = 'Stop'
if ($BuildId -notmatch '^[A-Za-z0-9-]+$') { throw 'BuildId may contain only letters, digits and hyphens.' }
$projectRoot = $PSScriptRoot
# Tool and signing paths are local inputs, never copied into source or APK assets.
$javaRoot = $env:LM_JAVA_HOME
$buildTools = $env:LM_BUILD_TOOLS
$androidJar = $env:LM_ANDROID_JAR
$compiler = $env:LM_KOTLIN_HOME
$dexCompiler = $env:LM_D8_JAR
foreach ($value in @($javaRoot,$buildTools,$androidJar,$compiler,$dexCompiler)) { if ([string]::IsNullOrWhiteSpace($value)) { throw 'Set LM_JAVA_HOME, LM_BUILD_TOOLS, LM_ANDROID_JAR, LM_KOTLIN_HOME and LM_D8_JAR; see README.' } }
$config = Get-Content -LiteralPath "$projectRoot\build-config.json" -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath $dexCompiler -Algorithm SHA256).Hash -ne '7712EDBAE6A71F35937FFC1BD5F4C202919EF2A5E58D3C3544015A868A3CF457') { throw 'Pinned D8 checksum mismatch.' }
$output = Join-Path $projectRoot "out\$BuildId"
if (Test-Path -LiteralPath $output) { throw 'Build directory already exists; use a new BuildId.' }
foreach ($required in @("$javaRoot\bin\java.exe","$buildTools\aapt2.exe",$androidJar,"$compiler\lib\kotlin-compiler.jar",$dexCompiler)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing local tool: $required" }
}
New-Item -ItemType Directory -Path "$output\classes","$output\dex","$output\gen","$output\tests" -Force | Out-Null
& "$projectRoot\tests\RecoveryDialogContractTests.ps1" -ProjectRoot $projectRoot | Tee-Object -FilePath "$output\recovery-dialog-tests.txt"
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaRoot
    # Modern Gradle owns namespace/applicationId. Standalone aapt2 needs the package
    # in its input, so add it to a generated copy without changing the source.
    [xml]$localManifest = Get-Content -LiteralPath "$projectRoot\app\src\main\AndroidManifest.xml" -Raw
    $localManifest.DocumentElement.SetAttribute('package', $config.package)
    $localManifest.Save("$output\AndroidManifest.xml")
    & "$buildTools\aapt2.exe" compile --dir "$projectRoot\app\src\main\res" -o "$output\compiled-res.zip"
    if ($LASTEXITCODE -ne 0) { throw 'Resource compile failed.' }
    & "$buildTools\aapt2.exe" link -o "$output\base.apk" -I $androidJar --manifest "$output\AndroidManifest.xml" -R "$output\compiled-res.zip" --auto-add-overlay --java "$output\gen" --min-sdk-version $config.minSdk --target-sdk-version $config.targetSdk
    if ($LASTEXITCODE -ne 0) { throw 'Resource/manifest link failed.' }
    $kotlinSources = @((Get-ChildItem -LiteralPath "$projectRoot\app\src\main\java","$projectRoot\shared\src" -Filter '*.kt' -Recurse -File).FullName)
    & "$javaRoot\bin\java.exe" -cp "$compiler\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -Xlambdas=class -classpath "$androidJar;$compiler\lib\kotlin-stdlib.jar" -d "$output\classes" @kotlinSources
    if ($LASTEXITCODE -ne 0) { throw 'Kotlin Android compile failed.' }
    $model = "$projectRoot\app\src\main\java\com\shieldtools\launchermanager\SafetyModel.kt"
    $discoveryModel = "$projectRoot\app\src\main\java\com\shieldtools\launchermanager\DiscoveryModel.kt"
    $backendSources = @((Get-ChildItem -LiteralPath "$projectRoot\shared\src" -Filter '*.kt' -File).FullName)
    & "$javaRoot\bin\java.exe" -cp "$compiler\lib\*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 1.8 -classpath "$compiler\lib\kotlin-stdlib.jar" -d "$output\tests" $model $discoveryModel @backendSources "$projectRoot\tests\SafetyTests.kt" "$projectRoot\tests\DiscoveryTests.kt" "$projectRoot\tests\backend\BackendTests.kt" "$projectRoot\tests\OnDeviceCommandTests.kt" "$projectRoot\tests\NativeAdbTests.kt" "$projectRoot\tests\ForegroundTests.kt" "$projectRoot\tests\ConnectionStateTests.kt"
    if ($LASTEXITCODE -ne 0) { throw 'Safety test compile failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.SafetyTestsKt | Tee-Object -FilePath "$output\safety-test-results.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Safety tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.DiscoveryTestsKt "$projectRoot\tests\fixtures\shield-android11-home.tsv" | Tee-Object -FilePath "$output\discovery-test-results.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Discovery tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.backend.BackendTestsKt | Tee-Object -FilePath "$output\backend-test-results.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Backend/security simulation tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.backend.OnDeviceCommandTestsKt | Tee-Object -FilePath "$output\on-device-command-tests.txt"
    if ($LASTEXITCODE -ne 0) { throw 'On-device command contract tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.backend.NativeAdbTestsKt | Tee-Object -FilePath "$output\native-adb-tests.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Native ADB protocol/security tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.backend.ForegroundTestsKt "$projectRoot\tests\fixtures\foreground-shield" | Tee-Object -FilePath "$output\foreground-tests.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Foreground capture/regression tests failed.' }
    & "$javaRoot\bin\java.exe" -cp "$output\tests;$compiler\lib\kotlin-stdlib.jar" com.shieldtools.launchermanager.backend.ConnectionStateTestsKt | Tee-Object -FilePath "$output\connection-state-tests.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Connection state tests failed.' }
    $classFiles = @((Get-ChildItem -LiteralPath "$output\classes" -Filter '*.class' -File -Recurse).FullName)
    & "$javaRoot\bin\java.exe" -cp $dexCompiler com.android.tools.r8.D8 --version | Set-Content -LiteralPath "$output\dex-compiler.txt"
    & "$javaRoot\bin\java.exe" -cp $dexCompiler com.android.tools.r8.D8 --min-api $config.minSdk --release --lib $androidJar --output "$output\dex" @classFiles "$compiler\lib\kotlin-stdlib.jar" "$compiler\lib\annotations-13.0.jar" *> "$output\dex-build.log"
    if ($LASTEXITCODE -ne 0) { throw 'D8 failed.' }
    if ((Get-Item -LiteralPath "$output\dex-build.log").Length -gt 0) { Get-Content -LiteralPath "$output\dex-build.log" -Tail 12 }
    Copy-Item -LiteralPath "$output\base.apk" -Destination "$output\unsigned.apk"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open("$output\unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
    try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, "$output\dex\classes.dex", 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null }
    finally { $archive.Dispose() }
    & "$buildTools\zipalign.exe" -f 4 "$output\unsigned.apk" "$output\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'Alignment failed.' }
    $signing = $env:LM_SIGNING_DIR
    if ([string]::IsNullOrWhiteSpace($signing)) { throw 'LM_SIGNING_DIR must reference the existing private signing identity. No key generation permitted.' }
    $passwordFile = Join-Path $signing 'local-key-password.txt'
    $keyFile = Join-Path $signing 'launcher-manager-local.p12'
    if (!(Test-Path -LiteralPath $keyFile) -or !(Test-Path -LiteralPath $passwordFile)) { throw 'Existing signing key/password file unavailable; refusing a new identity.' }
    $apk = Join-Path $output "shield-launcher-manager-$($config.versionName).apk"
    & "$javaRoot\bin\java.exe" -jar "$buildTools\lib\apksigner.jar" sign --ks $keyFile --ks-pass "file:$passwordFile" --out $apk "$output\aligned.apk"
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & "$javaRoot\bin\java.exe" -jar "$buildTools\lib\apksigner.jar" verify --verbose --print-certs --min-sdk-version 30 --max-sdk-version 30 $apk | Tee-Object -FilePath "$output\signature-verification.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Android 11 signature verification failed.' }
    & "$buildTools\aapt.exe" dump badging $apk | Set-Content -LiteralPath "$output\apk-badging.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Badging check failed.' }
    & "$buildTools\aapt.exe" dump xmltree $apk AndroidManifest.xml | Set-Content -LiteralPath "$output\apk-manifest.txt"
    if ($LASTEXITCODE -ne 0) { throw 'Manifest check failed.' }
    & "$buildTools\zipalign.exe" -c 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'Final alignment verification failed.' }
    $hash = Get-FileHash -LiteralPath $apk -Algorithm SHA256
    [pscustomobject]@{Apk=$apk;SHA256=$hash.Hash;Bytes=(Get-Item -LiteralPath $apk).Length;BuildId=$BuildId;InstalledOnDevice=$false} | ConvertTo-Json | Tee-Object -FilePath "$output\artifact.json"
} finally { $env:JAVA_HOME = $oldJavaHome }
# No ADB, install, service, HOME or package-manager device operation is part of this build.





