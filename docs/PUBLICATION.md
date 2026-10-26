# Public source selection

PUBLIC-FILES.txt is the exact reviewed file allowlist. The root .gitignore denies all other files by default and adds defensive secret/artifact rules. New files require explicit review; never use git add -f to bypass this policy. No ignore pattern can prevent someone deliberately forcing a secret into Git.

The private public-source preview is under ignored local preparation data. It contains only allowlisted source before validation outputs; any out directory generated during validation remains private/ignored and must not be included in a source archive. Do not publish the working directory wholesale.

Keep private: .local, original full captures and historical reports, experimental archives, signing stores/passwords, workstation ADB keys, app identity files and transaction journals, tools and APK intermediates. Public test captures are minimal sanitized excerpts. Their complete originals remain privately preserved. App signing certificate fingerprints and the intentionally generic ADB identity comment are public, not secrets.

Before publication, compare every intended file with PUBLIC-FILES.txt, scan for credentials/private-key headers, personal paths and endpoint identifiers, inspect staged file names and the complete diff, and scan all reachable Git commits if any exist. .gitignore does not remove already tracked secrets. The curated local release repository contains only the reviewed public source and its scanned initial commit; private development history was not imported. Recheck all reachable history and staged contents before each push or any future import. The working project retains private evidence outside the curated repository.

License/ownership approval, private security-reporting route and final release artifact checks remain separate release gates. A clean pattern scan is not legal approval or an exhaustive security guarantee.

