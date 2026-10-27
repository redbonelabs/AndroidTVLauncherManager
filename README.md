# Android TV Launcher Manager

A lightweight, remote-friendly launcher manager by **redbone labs** for NVIDIA Shield TV Pro and NVIDIA Shield TV 2019 Tube on Android 11 / API 30. Discover installed HOME launchers, preview them, select a default with explicit consent, and restore journal-owned prior states without root or a separate helper.

**[Download v1.0.1 / versionCode 17](https://github.com/redbonelabs/AndroidTVLauncherManager/releases/tag/v1.0.1).** Application ID: `com.shieldtools.launchermanager`. This release packages the validated code-16 application behavior without further runtime changes. The v1.0.0 tag and release are retained.

## Supported hardware and validation

The native switching backend accepts only manufacturer NVIDIA, device `mdarcy` or `sif`, Android 11 / API 30, owner user 0 and non-root shell UID 2000. It also verifies that app and ADB peer identify the same device and boot. This is not universal Android TV support.

| Hardware | Verified results | Limits |
|---|---|---|
| Shield TV Pro (`mdarcy`) | Prior hardware validation on firmware 9.2.4: Stock, TV Detox 1.5.3 and Projectivy 4.72 discovery, switching and restoration. One controlled TV Detox reboot-persistence test, remembered authorization/manual reconnect and exact Stock-state restoration passed. | These are prior development-build results; not a new code-17 hardware test. Projectivy reboot persistence was not tested. |
| Shield TV 2019 Tube (`sif`), primary test unit | Native ADB and all three launcher cards verified. Stock activation/restoration passed. With code 16, Projectivy 4.72 setup in Preview, activation and supported restoration to TV Detox 1.5.2 passed. Physical HOME returned to Detox and exact original Stock package/component settings **3/0** were restored without workstation recovery in the final successful trials. | One restoration Keep confirmation timed out before final committed restoration. No Tube reboot-persistence test was performed. |
| Second Shield TV 2019 Tube (`sif`) | Code-16 Manager installation/upgrade succeeded; Stock, Projectivy 4.72 and TV Detox 1.5.2 are installed. Projectivy discovery was verified before Detox was added. | Full switching/restoration and a final three-launcher Manager discovery check were not independently performed on this unit. |

The same APK supports the Tube's 32-bit ARM userspace: Manager contains no native ABI payloads. Hardware/API checks still apply. The final code-17 release APK was validated statically and by automated tests; it was not installed on a Shield during release preparation.

## Features and boundaries

- Dynamic HOME-launcher discovery, including enabled and disabled launchers where Android exposes them.
- Selected, unset/chooser and unverified HOME states; the visible launcher is never assumed to be Android's default.
- Explicit outgoing local ADB connection with private per-install credentials and identity checks.
- Setup/Preview before alternate-launcher activation, followed by separate switching consent.
- Stock-only priority fallback, strict HOME/foreground verification, a separate Keep decision and durable journal-owned restoration.

No root, generic console, network scan, telemetry, helper, listening service, background service, boot receiver, accessibility interception or APK installer is included. Preview cannot guarantee Back returns to Manager; reopen Manager from its app tile.

## Installation and application-data-preserving upgrades

Obtain `shield-launcher-manager-1.0.1.apk` and `SHA256SUMS.txt` from the [official release](https://github.com/redbonelabs/AndroidTVLauncherManager/releases/tag/v1.0.1). Verify the checksum and [signing certificate](docs/SIGNING.md) first.

```powershell
Get-FileHash '<downloaded-release.apk>' -Algorithm SHA256
$adb = '<platform-tools-directory>\adb.exe'
$shield = '<your-verified-shield-address>:5555'
& $adb connect $shield
& $adb -s $shield get-state
& $adb -s $shield install --no-incremental -r '<verified-release.apk>'
```

Target only a separately verified Shield. Same-package, same-signer upgrades with an increasing versionCode preserve Manager data under Android's normal update behavior. Do not uninstall, clear data, replace credentials or remove journals to upgrade. Different signers are not ordinary compatible updates. Installing Manager does not select a HOME launcher. Install other trusted launchers separately; this app does not install them.

## If Android shows its HOME chooser

Installing another launcher may invalidate a saved HOME preference. An already visible launcher can remain on screen even though Android no longer has a selected default.

Manager displays **No default selected** with guidance. Press physical HOME, select your intended launcher through Android's normal chooser, choose **Always** if offered, then return to Manager and Refresh. Android may label a launcher generically as HOME; Advanced Status shows the exact resolved component. Only a verified default receives **Already HOME**.

Read-only native ADB can remain authenticated while HOME is unset. HOME ambiguity or disagreement is reported separately from transport/authentication failure. Switching remains blocked until a safe default HOME baseline is established; no default is silently chosen.

## Native ADB connection

1. Enable the Shield's Developer Options/network debugging yourself if you choose switching. Manager never changes these settings.
2. Select **Connect local ADB**, read the disclosure and manually approve Android's matching authorization prompt. Always allow is optional. Workstation ADB authorization is separate.
3. Wait for **Connected**. Connection and read-only identity/HOME inspection alone do not select a launcher.

The only destination is `127.0.0.1:5555`. A socket does not survive reboot or process death: explicitly reconnect. Saved credentials may avoid a new prompt, but do not guarantee authorization after revocation, expiry or data loss. No automatic reconnect or switch retry occurs.

## Prepare, activate, Keep and restore

Keep a verified independent workstation recovery route available. Record the current HOME and Stock's exact package AND component settings; never assume setting 0.

1. For an alternate launcher, choose **Activate HOME** and use **Preview / finish setup** if first-run setup is incomplete. Complete onboarding and decide genuine Android permission prompts yourself. Preview runs outside the switch transaction and its timer; it does not grant permissions or select HOME. Manager does not require launcher override or accessibility services.
2. Return to Manager, choose Activate HOME again, acknowledge that setup reached the launcher's normal home screen, and choose **Review activation**. Stock selection retains its existing direct recovery/consent route. Disabled alternate launchers are not automatically enabled.
3. Acknowledge independent recovery readiness, review the exact target and changes, then confirm within **30 seconds**. An expired review requires a fresh review; no automatic retry occurs.
4. Actual activation still requires the exact HOME activity to pass the strict short foreground check, actual HOME assignment and physical-HOME foreground checks. Setup is not mistaken for successful activation. Moving setup to Preview does not relax the 3.5-second strict prelaunch verification.
5. Select **Keep within its separate 30-second deadline** before pausing to report a result. Restore, Back or timeout attempts rollback while the process and ADB remain usable. A timed-out dialog may remain visible; it is not proof a confirmation is still live.
6. **Restore Previous** uses validated committed history and exact touched package settings. **Restore Stock** cannot claim ownership of pre-existing Stock disabling without the required journal proof. Use a separately reviewed independent recovery plan when ownership is unavailable.

**Recover pending** returns toward the interrupted transaction's recorded prior HOME, which may differ from the configuration at the start of a longer testing session. Inspect its target before confirming. Never fabricate, delete or import a journal to bypass safeguards.

## Limitations and recovery

- No automatic rollback guarantee across process termination, reboot, lost ADB, external changes or storage/journal corruption. Preview may outlive Manager; reconnect explicitly after returning if needed.
- A working prior HOME, identities and package settings must be independently verifiable. Unknown foreground, incomplete setup or changed signer/version/state blocks completion.
- Stock remains installed. Making Stock HOME requires it to be enabled; Stock HOME and Stock disabled-user state cannot coexist.
- A normal default HOME change alone does not imply Stock was disabled. Exact restoration concerns the actual recorded state, not a universal 0/1 assumption.
- No Tube reboot-persistence result, universal firmware support, independent-PC bit-for-bit build reproducibility or exhaustive loss/revocation testing is claimed.

See [independent recovery](RECOVERY-NATIVE-ADB.md), [security reporting](SECURITY.md), [build instructions](docs/BUILDING.md), [release notes](RELEASE-NOTES.md) and [publication rules](docs/PUBLICATION.md).

Copyright (c) 2026 redbone labs. Apache-2.0; see [LICENSE](LICENSE), [NOTICE](NOTICE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
