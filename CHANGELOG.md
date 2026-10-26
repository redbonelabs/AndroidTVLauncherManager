# Changelog

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

