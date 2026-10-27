# Security

Only the TV entry Activity is exported; it consumes no external switching commands. INTERNET is the sole permission. No scanner, generic console, helper, listener, background service, boot receiver or accessibility interception is included.

Explicit Connect creates per-install RSA credentials encrypted using Android Keystore AES-GCM in app-private no-backup storage. No shared/workstation key is shipped. Same-signer upgrades preserve data; data clearing, uninstall or key loss can destroy access. An unreadable existing identity is not silently regenerated. A compromised OS/app UID is outside this protection.

Classic ADB grants shell authority and is not mutually authenticated TLS. Loopback plus same-device boot/user/model/API/non-root checks constrain the peer but do not defeat a compromised OS/proxy. Keep debugging away from untrusted networks. Manager never enables debugging or changes developer settings.

Typed commands, explicit review, Stock-only consent, target identity/state, actual HOME/foreground verification and durable journal ownership guard switching. Confirmation expires after 30 seconds without reuse; Keep has a separate 30-second deadline. Process death, reboot, lost ADB or corruption can prevent rollback. Keep independent recovery; do not clear history to bypass an error.

Supported profiles are NVIDIA Shield Pro mdarcy and Shield TV 2019 Tube sif on API 30. Prior Pro switching/restoration and one Detox reboot/manual reconnect were verified. Primary Tube Stock and Projectivy switching/restoration passed in the final successful trials; the second Tube has installation/discovery evidence only. Tube reboot persistence and exhaustive loss/revocation cases are unverified. See README for exact evidence boundaries.

## Report a vulnerability privately

GitHub Private Vulnerability Reporting is enabled. Open https://github.com/redbonelabs/AndroidTVLauncherManager/security/advisories/new or choose Security → Advisories → Report a vulnerability. Use that private form rather than public issues. Describe affected version, reproduction and impact; redact keys, credentials, private addresses and personal device data.

The reporting feature was enabled and verified before source and release assets were uploaded. If the form is unavailable, do not post sensitive details publicly; wait for the maintainer to enable it. No security email address or response-time guarantee is invented.


Version 1.0.1 separates HOME selection readiness from authenticated ADB state. Unset or disagreeing HOME cannot authorize switching. Preview/setup occurs before a transaction; it does not auto-grant permissions, enable accessibility or assign HOME. The existing strict identity, journal and final HOME checks are retained. Recover pending targets the interrupted transaction's recorded baseline, not an assumed original session HOME. Published v1.0.0 is unchanged.

