# v1.0.1

Android TV Launcher Manager by **redbone labs**. Version **1.0.1 / versionCode 17**, package `com.shieldtools.launchermanager`, Apache-2.0.

## Changes

- Adds NVIDIA Shield TV 2019 Tube (`sif`) support alongside Shield TV Pro (`mdarcy`), restricted to NVIDIA / Android 11 / API 30 with the existing same-device, boot, owner-user and shell identity checks.
- One ABI-independent APK supports the Tube's 32-bit ARM userspace and dynamically discovers Stock, TV Detox, Projectivy and other eligible HOME launchers.
- Distinguishes selected, unset/chooser and unverified HOME states. A visible launcher is not assumed to be the default; Android's normal chooser establishes a missing default.
- Fixes misleading ADB connection failures caused by HOME chooser validation. Authenticated read-only ADB and HOME readiness are separate; ambiguous or disagreeing HOME still blocks switching.
- Adds an explicit Preview/setup stage before alternate-launcher activation. Legitimate onboarding and manually approved permission screens can complete outside the transaction, preventing premature prelaunch rollback during setup. The strict 3.5-second activity verification is retained for actual activation.
- Preserves fresh setup acknowledgement, independent-recovery acknowledgement, exact listed-change consent, 30-second review expiry, separate 30-second Keep, journal ownership, actual HOME verification and exact-state restoration.

## Hardware evidence and limits

- **Shield Pro / mdarcy:** prior Stock, TV Detox 1.5.3 and Projectivy 4.72 switching/restoration passed. One controlled TV Detox reboot-persistence/manual native reconnect/exact Stock restoration trial passed on firmware 9.2.4.
- **Primary Shield Tube / sif:** native ADB and all three launchers were verified. Stock activation/restoration and code-16 Projectivy 4.72 setup/activation/restoration to TV Detox 1.5.2 passed; exact original Stock states 3/0 and physical HOME were verified without workstation recovery in the final successful trials. One restoration Keep confirmation expired before final committed restoration.
- **Second Shield Tube / sif:** code 16 installed; all three launchers installed; Projectivy discovery verified. Full switching/restoration was not independently performed.
- This release changes version metadata and documentation from the validated code-16 runtime baseline. The code-17 release artifact was not installed on either Shield during publication. No Tube reboot-persistence or Projectivy reboot-persistence result, universal Android TV support, or automatic recovery across process death/reboot/lost authorization is claimed.

## Upgrade and use

Use an in-place `adb -s <verified-device> install --no-incremental -r <verified-apk>` update. The package and original signing identity are retained. Do not uninstall, clear data, reset credentials or erase journals. Same-signer Android upgrades preserve existing app data under normal update behavior.

Adding a launcher may cause Android to show its HOME chooser. Choose the intended default through Android and select Always if offered; Manager does not silently choose it. For a new alternate launcher, complete Preview/setup first, then review activation and select Keep promptly. Reconnect local ADB explicitly after process death/reboot. A stale Keep dialog is not a live ticket; inspect current status before further action.

Recovery is transaction-specific and journal-owned. Pre-existing disabled Stock is not automatically owned by Manager. Keep the independently verified recovery procedure and original HOME/package/component baseline available. Do not reset data or fabricate history to bypass a refusal.

## Validation and artifacts

Clean release validation: **216 JVM tests, 17 consent/setup source-contract checks, 14 mocked recovery-script checks, 50 artifact/security checks**. No device operations are part of the build or this release task.

- APK: `shield-launcher-manager-1.0.1.apk`
- Size: 787569 bytes
- APK SHA-256: `23dfc8ddedd1f0b1da45a2ea2a061a3af3e80ff26236bae1289ee7fdea52bb01`
- Signing certificate SHA-256: `efde12513ce1b08e0ea49477037eb214fe91a39755a451e3f739849e5723455b`
- Checksums: `SHA256SUMS.txt`

The original certificate is retained, including its historical Local Development subject label. Required licenses/notices remain included. Obtain files from [v1.0.1](https://github.com/redbonelabs/AndroidTVLauncherManager/releases/tag/v1.0.1); [v1.0.0](https://github.com/redbonelabs/AndroidTVLauncherManager/releases/tag/v1.0.0) is unchanged. Report vulnerabilities through the repository's private reporting form linked in SECURITY.md; do not post credentials or private device logs publicly.

Copyright (c) 2026 redbone labs.
