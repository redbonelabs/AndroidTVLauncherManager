# Windows build

Use PowerShell 7, JDK 21, Android SDK platform 36/build-tools 36.0.0, Kotlin compiler 2.2.21 and D8 8.13.19. The validated path is the standalone PowerShell script, not an undocumented Gradle installation. Minimum API 30, target 34. Gradle metadata is retained but there is no Gradle wrapper or dependency lockfile; do not claim that path is validated.

Obtain JDK 21 from a trusted JDK vendor; Android command-line tools/platform-tools from https://developer.android.com/tools; install `platforms;android-36` and `build-tools;36.0.0` with sdkmanager. Kotlin compiler 2.2.21: https://github.com/JetBrains/kotlin/releases/tag/v2.2.21 (compiler archive). D8 is supplied by https://storage.googleapis.com/r8-releases/raw/8.13.19/r8.jar . Verify its SHA-256 is `7712edbae6a71f35937ffc1bd5f4c202919ef2a5e58d3c3544015a868a3cf457` before use; the build enforces it.

The Kotlin archive supplies `kotlin-stdlib.jar` 2.2.21 and `annotations-13.0.jar`. The latter was verified byte-for-byte against https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.jar and its exact-version POM/source copyright inspected. See THIRD-PARTY-NOTICES.md. No tool archive or private signing file belongs in this repository.

Set paths for your computer; nothing requires a particular drive or username:

```powershell
$env:LM_JAVA_HOME = '<jdk-21-directory>'
$env:LM_BUILD_TOOLS = '<android-sdk>\build-tools\36.0.0'
$env:LM_ANDROID_JAR = '<android-sdk>\platforms\android-36\android.jar'
$env:LM_KOTLIN_HOME = '<kotlin-2.2.21>\kotlinc'
$env:LM_D8_JAR = '<tools>\r8-8.13.19.jar'
$env:LM_SIGNING_DIR = '<private-existing-signing-directory>'
.\Build-Local.ps1 -BuildId local-validation
.\Verify-LocalArtifact.ps1 -BuildId local-validation
```

Use a new BuildId for each run. The existing signing directory contains `launcher-manager-local.p12` and `local-key-password.txt`; preserve those established inputs outside Git. The build refuses to generate another identity. Official release signing uses the approved signer in docs/SIGNING.md. Builds signed differently do not ordinarily upgrade official installations; the current artifact verifier pins the official certificate.

Build runs 187 JVM tests and 12 dialog source-contract checks, compiles resources/Kotlin/DEX, aligns/signs the APK and records hashes. Artifact verification runs 45 checks including identity, Android-11-compatible signature/DEX, permissions, absence of keys/assets/helpers and test results. No ADB operation or installation is part of either script.

`docs/BUILD-INPUT-HASHES.json` records the locally validated tools and runtime inputs without local paths. Compare downloaded inputs using Get-FileHash. Hash provenance for the exact downloaded distribution must be reviewed; local hashes are not a substitute for trusted acquisition. Full JDK/SDK environment identity is not completely captured by hashing java.exe/android.jar alone.

## Reproducibility limits

These instructions reproduce the local build procedure with pinned versions/configurable locations. Independent clean-PC and bit-for-bit signed-APK reproducibility have NOT been established. ZIP timestamps, environment and signing can affect bytes; compare package/version, signer, test output and logical contents before claiming identical artifacts. The script retains local absolute paths only in ignored output evidence, not application credentials. Before v1.0, provision a clean environment, verify the source allowlist and rerun this procedure with the approved signing process. Do not publish development outputs as v1.0.
