# v1.0.0

Published at https://github.com/redbonelabs/AndroidTVLauncherManager/releases/tag/v1.0.0 .

A lightweight, remote-friendly launcher manager by **redbone labs**, licensed under Apache-2.0.

- Discover HOME launchers, detect current HOME and preview alternatives.
- Authenticate to this Shield's local ADB explicitly, without root or a separate helper.
- Review launcher selection and Stock-only priority fallback before changes.
- Verify actual HOME/foreground and Keep, with journal-owned exact-state restoration.
- Reject expired confirmations with a clear 30-second timeout message.

**Compatibility:** hardware-tested on NVIDIA Shield Pro mdarcy/Android TV11/API30/firmware9.2.4. Stock, TV Detox1.5.3 and Projectivy4.72 switching/restoration were tested. One Detox reboot-persistence trial passed with manual reconnect and remembered authorization. Other Android TVs/Shield variants and Projectivy reboot persistence are not verified.

**Upgrade:** application ID `com.shieldtools.launchermanager`, versionName1.0.0, versionCode13. Same approved certificate as development builds: use an in-place upgrade; do not uninstall or clear data. App data/credentials/journals remain under Android's normal same-signer update behavior. The final release artifact was not installed or hardware-tested; application logic is unchanged from the tested development baseline.

**Setup:** enable network debugging yourself, select Connect local ADB and approve the matching Android prompt. Remembered authorization does not keep the socket alive across reboot: reconnect explicitly. Review expires after30seconds; Keep has its own30second deadline. Retain independent Stock recovery. Missing journal ownership, corruption or lost authorization can require workstation recovery; no automatic rollback guarantee or accessibility interception is provided.

Sources include required license/notices; the signed APK and SHA-256 will accompany the published release. See README, security policy and recovery/build instructions. Private Vulnerability Reporting must be enabled before publication; do not submit credentials or sensitive device logs in public issues.

## Artifact verification

APK: `shield-launcher-manager-1.0.0.apk`
SHA-256: `974333280afd72c4d6b4b79194bf2976d053931205e8490c40322fa6cf6a48e2` 
Signer certificate SHA-256: `efde12513ce1b08e0ea49477037eb214fe91a39755a451e3f739849e5723455b`.

Signed artifact package/signature/version compatibility with existing development installs was verified statically; no Shield installation or hardware test was performed for this release artifact.


