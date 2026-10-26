# Release signing policy

Owner approved the EXISTING signing identity for public releases. No key was created, rotated or changed.

- Package: `com.shieldtools.launchermanager`.
- Certificate SHA-256: `efde12513ce1b08e0ea49477037eb214fe91a39755a451e3f739849e5723455b`.
- RSA-3072 / SHA256withRSA; self-signed subject `CN=Shield Launcher Manager Local Development`.
- Valid 22 October 2026 through 19 October 2036. The subject label does not prevent release use; expiry needs an advance migration plan.

Existing builds using this signer can upgrade in place with increasing version codes and the same application ID. Preserve private app data, credentials and journals; no uninstall/data-clear is needed. Different keys are not interchangeable. Future rotation requires an approved Android-compatible plan, not silent replacement.

Keep the keystore/password outside Git. The owner chose local-only signing storage for this release; this is NOT an independent disaster-recovery backup. The original and preferred copies on the same computer do not protect against loss of that computer/storage. The preferred keystore unlocks with the existing credential and retains alias manager and the approved certificate. Loss of key/credentials can prevent compatible future updates. Controlled access and encrypted independent backups are recommended; no such backup is claimed or verified. Publish only fingerprints/certificates, never secrets. Do not place this long-term key in public CI.

Independent source builders using another signer cannot ordinarily replace an official install in place. The official verifier pins the approved signer. No new-key generation is part of this preparation.

Reference: https://developer.android.com/studio/publish/app-signing

