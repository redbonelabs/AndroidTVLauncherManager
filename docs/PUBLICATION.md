# Public source selection and release workflow

PUBLIC-FILES.txt is the exact reviewed source allowlist. The root .gitignore denies all other files by default and adds defensive secret/artifact rules. New files require explicit review; never use git add -f to bypass this policy. Ignore rules do not sanitize already tracked history.

Use the existing curated public Git checkout, existing origin and history. Do not initialize a replacement repository, import private development history, rewrite old commits, force-push, or recreate the v1.0.0 tag. Verify the remote branch before committing and again before pushing. Configure release author identity locally for this repository; unrelated repositories need no change.

Synchronize only allowlisted files from the active project. Build outputs generated in the curated checkout remain ignored. Publish a Git archive of the committed tree or GitHub's tag source archives, never the whole working directory.

Keep private: local preparation data, complete captures and reports, original/private source backups, experimental archives, signing stores/passwords, workstation ADB keys, per-install identity files, transaction journals, tools and APK intermediates. Public test captures are minimal sanitized excerpts. Public certificate fingerprints and the intentionally generic ADB identity comment are not credentials.

Before each push, compare tracked/staged files with the allowlist, scan staged contents and every reachable commit for known credential material and private markers, inspect the complete diff, and verify root/staged source hashes. Historical commits must match their own recorded allowlist, not a later expanded one. Do not print secrets during scans. A targeted scan is a release gate, not a claim that all possible secrets can be detected.

Build the clean release from the synchronized source using the approved private signing inputs and configurable local tool paths. Keep those inputs outside Git. Re-run all automated suites and artifact checks, verify version/package/signer, and verify the release runtime matches the hardware-validated development baseline. No Shield commands are part of this process.

Use normal authenticated GitHub tooling. If login is missing, let the owner complete browser authentication; never request passwords, tokens or private keys in chat. Verify the intended account, repository write permission and private vulnerability reporting. Commit, create an annotated new release tag, push source/tag without force, and publish only the signed release APK, checksum and normal source archives. Download the published assets and verify their bytes and tag/commit correspondence.

Retain the existing Apache-2.0 license, applicable notices and original signing identity. Release authorization and provenance carry forward from the owner; no replacement key or new repository is needed. Do not modify an already published older release as part of a new version.
