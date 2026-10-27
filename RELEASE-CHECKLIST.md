# v1.0.1 release gates

Release: versionName 1.0.1, versionCode 17, annotated tag v1.0.1. Runtime source must match the validated code-16 baseline; only release metadata, build-report wording and documentation change during packaging.

## Before commit and publication

- Preserve the existing public repository and history; verify origin, local author identity, remote synchronization and account write permission. Never force-push or recreate v1.0.0.
- Use PUBLIC-FILES.txt as the exact source allowlist. Stage no private tools, local configuration, APKs, signing stores/passwords, credentials, diagnostic captures or transaction journals.
- Retain Apache-2.0, copyright, third-party notices and private security reporting.
- Document mdarcy and sif / NVIDIA / API 30 support with distinct Pro, primary Tube and second Tube evidence. Do not infer unperformed hardware tests.
- Build from the synchronized public source with the original signer. Require 216 JVM tests, 17 consent/setup checks, 14 mocked recovery checks and 50 artifact/security checks.
- Verify package/version, API-30 signature, original certificate, absence of native ABI payloads/debug behavior/unintended permissions, and data-preserving upgrade compatibility.
- Compare release runtime source and DEX with code 16. Review any difference before publishing.
- Generate SHA256SUMS.txt. Review staged diff, exact allowlist, full reachable history, release asset bytes, private markers and known credential leakage without printing secrets.

## Publication procedure

1. Commit the reviewed source with the established redbone labs identity.
2. Create an annotated v1.0.1 tag on that commit; preserve the existing v1.0.0 ref/release.
3. Re-fetch before push and verify the update is a fast-forward; stop on unexpected remote work.
4. Push main and the new tag without force.
5. Publish v1.0.1 with the signed APK and SHA256SUMS.txt. GitHub supplies source archives for the tag.
6. Verify release metadata, links, downloaded APK/checksum, signer, source commit, annotated tag and public documentation.

The owner authorized this release workflow. Authentication must use normal browser/credential-manager flows; never request secrets in chat. Stop on failed tests, signing, privacy audit or integrity checks. No Shield contact, installation, switching or configuration change is part of publication.
