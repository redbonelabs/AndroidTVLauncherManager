# Security

Only the TV entry Activity is exported; it consumes no external switching commands. INTERNET is the sole permission. No scanner, generic console, helper, listener, background service, boot receiver or accessibility interception is included.

Explicit Connect creates per-install RSA credentials encrypted using Android Keystore AES-GCM in app-private no-backup storage. No shared/workstation key is shipped. Same-signer upgrades preserve data; data clearing, uninstall or key loss can destroy access. An unreadable existing identity is not silently regenerated. A compromised OS/app UID is outside this protection.

Classic ADB grants shell authority and is not mutually authenticated TLS. Loopback plus same-device boot/user/model/API/non-root checks constrain the peer but do not defeat a compromised OS/proxy. Keep debugging away from untrusted networks. Manager never enables debugging or changes developer settings.

Typed commands, explicit review, Stock-only consent, target identity/state, actual HOME/foreground verification and durable journal ownership guard switching. Confirmation expires after 30 seconds without reuse; Keep has a separate 30-second deadline. Process death, reboot, lost ADB or corruption can prevent rollback. Keep independent recovery; do not clear history to bypass an error.

Tested configuration: Shield Pro mdarcy/API 30, including one Detox reboot and manual reconnect with remembered authorization. Other devices and all loss/revocation cases are unverified.

## Report a vulnerability privately

GitHub Private Vulnerability Reporting is enabled. Open https://github.com/redbonelabs/AndroidTVLauncherManager/security/advisories/new or choose Security → Advisories → Report a vulnerability. Use that private form rather than public issues. Describe affected version, reproduction and impact; redact keys, credentials, private addresses and personal device data.

The reporting feature was enabled and verified before source and release assets were uploaded. If the form is unavailable, do not post sensitive details publicly; wait for the maintainer to enable it. No security email address or response-time guarantee is invented.

