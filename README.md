# Android TV Launcher Manager

A lightweight launcher manager by **redbone labs** for NVIDIA Shield Pro. Discover HOME launchers, preview them, switch using the TV remote and restore NVIDIA Stock without root or a separate helper.

**v1.0.0 / versionCode 13 is prepared locally, not published.** The hardware-verified development baseline is 0.4.7-dev/code 12; this release retains its application logic. Application ID: `com.shieldtools.launchermanager`.

## Compatibility

Tested on Shield Pro **mdarcy**, Android TV 11/API 30, NVIDIA firmware 9.2.4. Stock, TV Detox 1.5.3 and Projectivy 4.72 discovery, remote navigation, switching and restoration passed. One controlled Detox reboot verified HOME persistence, manual ADB reconnection with remembered authorization and exact Stock-state restoration. The final 0.4.7 check verified expiry rejection without switching.

Other TVs, Shield variants and Android versions are **not verified**. Native switching rejects devices other than NVIDIA/mdarcy/API 30. Projectivy reboot persistence was not tested.

## Features and boundaries

- Discover launchers and identify actual HOME; preview without selecting HOME.
- Explicit local ADB connection, per-install private credentials and reviewed switching.
- Optional Stock-only priority fallback; Stock remains installed with its data.
- Actual HOME/foreground verification, separate Keep decision and durable recovery history.

No root, helper, network scan, telemetry, generic console, accessibility interception, background service or APK installer. Preview cannot guarantee Back returns to Manager; reopen it from its app tile.

## Installation and upgrades

When published, obtain the APK only from `redbonelabs/AndroidTVLauncherManager` releases. No v1.0 download exists until the maintainer publishes the release. Verify the published SHA-256 and signing certificate first.

```powershell
$adb = '<platform-tools-directory>\adb.exe'
$shield = '<your-verified-shield-address>:5555'
& $adb connect $shield
& $adb -s $shield get-state
& $adb -s $shield install -r '<verified-release.apk>'
```

Authorize only your verified Shield/workstation. Same-package installations with the approved signer can upgrade in place with an increasing version code; do not uninstall or clear data. Different signers are not ordinary compatible upgrades. See [signing policy](docs/SIGNING.md).

## Initial ADB connection

1. Enable Shield Developer Options/network debugging yourself if you choose native switching. Manager never enables debugging or changes developer settings.
2. Select **Connect local ADB**, read the disclosure and approve Android's matching authorization request. Always allow is optional. Workstation authorization is separate.
3. Wait for **Connected**. Disconnected, Connecting and Connection Failed are distinct; failures retain their reason. Connection alone changes no launcher state.

Manager connects only to `127.0.0.1:5555`. A socket does not survive reboot/process death: select Connect again. Remembered credentials can avoid another prompt; revocation, expiry, data loss or unavailable debugging may require approval or independent recovery. No automatic reconnect or switch retry occurs.

## Switching and restoration

Keep a verified independent workstation recovery route ready. Record Stock's exact original state/component. Install trusted launchers separately.

1. Choose **Activate HOME**, acknowledge recovery readiness and Review. Verify target/disclosed changes.
2. Confirm within **30 seconds**. If expired, reopen Review; tickets are never silently renewed or reused.
3. Manager first tries selection with Stock enabled. Only explicit consent permits disabling `com.google.android.tvlauncher` for user 0 if its priority prevents selection. Unrelated packages are not disabled.
4. After verification choose **Keep within 30 seconds**, then test physical HOME. Cancel/timeout attempts rollback only while Manager and ADB remain available.
5. Choose **Restore Stock**, verify exact history settings, confirm and Keep. Original state 0 uses default-state; state 1 is also supported. Verify physical HOME.

Restore Previous uses the latest committed transaction; Recover pending handles interrupted transactions. Restoration can reject missing/invalid ownership after intermediate/external changes. It never guesses original state. Preserve errors/history and use independent recovery; do not clear data to bypass checks.

## Recovery, build and licensing

Process death, reboot, lost authorization or corruption can prevent rollback. Classic ADB grants shell authority and is not mutually authenticated TLS. Keep debugging away from untrusted networks. See [Stock recovery](RECOVERY-NATIVE-ADB.md) and [security](SECURITY.md).

[Windows build instructions](docs/BUILDING.md) describe the configurable standalone build. Bit-identical builds on independent PCs are not yet established.

Copyright (c) 2026 redbone labs. See [LICENSE](LICENSE), [NOTICE](NOTICE) and [third-party notices](THIRD-PARTY-NOTICES.md). Original-project release rights were confirmed by the commissioning owner; third-party attribution is retained. See [draft notes](RELEASE-NOTES.md) and [release checklist](RELEASE-CHECKLIST.md).


