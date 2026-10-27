# Changelog

## 1.0.1 - 26 October 2026

- Release metadata: versionName 1.0.1, versionCode 17; package and original signer retained. Runtime behavior is unchanged from validated code 16.
- Adds NVIDIA Shield TV 2019 Tube sif / Android 11 API 30 alongside the approved Shield Pro mdarcy profile, using one APK without native ABI restrictions. Other hardware/API levels remain unsupported.
- Separates selected, unset/chooser and unverified HOME; fixes misleading native ADB failure classification while preserving read-only authentication and blocking unsafe switching. A visible launcher is not assumed to be the default.
- Adds untimed Preview/onboarding preparation and fresh setup acknowledgement before alternate-launcher activation. Startup permission screens no longer run inside the short activation prelaunch window; strict final activity/HOME verification is retained.
- Preserves explicit consent, 30-second review and Keep deadlines, per-install credentials, journal ownership and exact-state restoration. Same-signer upgrades preserve app data under normal Android update behavior.
- Pro: prior Stock/Detox/Projectivy switching and restoration plus one Detox reboot-persistence trial. Primary Tube: Stock and Projectivy round trips, native ADB, physical HOME and exact original Stock 3/0 restoration verified in final successful trials without workstation recovery. Second Tube: installation/discovery evidence only; full switching not independently verified.
- 216 JVM tests, 17 consent/setup checks, 14 mocked recovery checks and 50 artifact/security checks. No release-time Shield installation/test; Tube reboot and Projectivy reboot persistence remain untested. Automatic rollback across process death, reboot or lost authorization is not promised.

## 1.0.1-dev / code 16 — launcher setup before activation, not published

- New alternate-launcher activation offers ordinary Preview/setup before the switch transaction. First-run onboarding and manually approved permission screens can finish without an activation deadline or rollback taking over.
- A fresh setup-completion acknowledgement leads to the existing recovery and activation consent. Actual persistent HOME, exact foreground, signer/identity, journal and restoration checks are unchanged.
- The code-15 HOME/ADB correction and transaction engine are retained unchanged. 216 JVM tests, 17 consent/setup checks, 14 mocked recovery checks and 50 artifact/security checks passed; device results are recorded separately in local build evidence.

## 1.0.1-dev / code 15 — HOME validation correction, not published

- Distinguish Android's chooser/unset HOME from a verified default; keep all discovered launchers visible and show system-chooser guidance.
- Keep authenticated native ADB read-only access separate from HOME readiness. Disagreeing HOME observations block changes without being classified as authentication failures.
- Preserve explicit consent, identity checks, journal ownership and exact restoration. An unset HOME blocks preparation and confirmation before journal writes or launcher operations.
- 211 JVM tests, 12 consent-dialog checks, 14 mocked recovery checks and 50 artifact/security checks passed. Hardware validation is recorded separately under the local build output.

## 1.0.1-dev / code 14 — local compatibility candidate, not published

- Single ABI-independent APK admits exact NVIDIA/mdarcy or experimental NVIDIA/sif profiles on API30; all existing peer equality, boot, owner-user, shell UID and authentication safeguards retained.
- Independent Stock recovery requires explicit target, expected supported device and desired enabled setting0 or1. No universal state0 baseline is assumed; exact state/HOME are verified. No journal is fabricated for pre-existing disabled Stock.
- Eight device-policy tests and fourteen mock recovery-script tests added. Existing transport, consent, HOME, journal and rollback tests retained. No Tube hardware test or GitHub publication performed.


## 1.0.0 — 26 October 2026

First public-release candidate by redbone labs, Apache-2.0. Package `com.shieldtools.launchermanager`, versionCode 13; existing approved signer retained for in-place upgrades.

- Remote-friendly launcher discovery, current HOME detection and preview.
- Explicit on-device ADB authentication and reviewed Stock-only priority fallback.
- Actual HOME/foreground checks, separate Keep decision and journal-owned restoration.
- Clear 30-second confirmation-expiry rejection without retry or ticket reuse.
- Public installation, connection, recovery, build and security documentation; dependency attribution and reviewed public-source exclusions.

Application logic is unchanged from hardware-verified 0.4.7-dev/code 12. Version metadata and distribution notices/docs are finalized for this candidate. No additional hardware tests were performed for v1.0.

Tested configuration: NVIDIA Shield Pro mdarcy, Android TV 11/API30, NVIDIA firmware9.2.4. Stock/Detox/Projectivy switching passed; one Detox reboot-persistence/manual reconnect/exact Stock restoration trial passed. Other devices and Projectivy reboot persistence are not verified.

Known limits: explicit Connect after reboot/process death; classic ADB shell authority; journal ownership may reject restoration after external/intermediate changes; no guaranteed rollback after loss of process/authorization; preview Back is not guaranteed.


