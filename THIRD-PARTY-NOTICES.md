# Third-party notices and provenance

Project source/modifications: Copyright (c) 2026 redbone labs. Intended license: Apache-2.0. Original-source redistribution rights were confirmed by the commissioning owner on 26 October 2026 for release under Apache-2.0 as redbone labs. Upstream notices are preserved; the source comparison below distinguishes implementation from license-template reuse.

## APK dependencies

| Component | Version | License and retained notices |
| --- | --- | --- |
| Kotlin JVM standard library | 2.2.21 | Apache-2.0; licenses/Kotlin-LICENSE.txt, Kotlin-COPYRIGHT.txt and Kotlin-NOTICE.txt |
| JetBrains annotations (including org.intellij.lang annotations) | 13.0 | Apache-2.0; licenses/Annotations-13.0-LICENSE.txt and Annotations-13.0-COPYRIGHT.txt (Copyright 2000-2012 JetBrains s.r.o.) |
| Relevant Kotlin upstream portions | as included in 2.2.21 | GWT Apache notice and ThreeTen BSD notice retained in licenses/gwt_license.txt and licenses/threetenbp_license.txt |

Corresponding notices/licenses are included as Android raw resources for APK distribution. The retained Kotlin-NOTICE is the upstream compiler-distribution notice, kept verbatim as supplemental attribution; it is not the sole runtime license statement. Kotlin's tagged licensing inventory identifies standard-library upstream origins; relevant notices above are retained conservatively for bundled JVM code. Build-only compiler/SDK/JDK/D8 are not redistributed in the APK or public source.

Exact annotations 13.0 POM declares Apache-2.0. The bundled JAR matches the official artifact byte-for-byte; copyright/license header was extracted from its official source archive. Sources:

- https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom
- https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0-sources.jar
- https://github.com/JetBrains/kotlin/blob/v2.2.21/license/README.md

## TV Box Tools and protocol references

Local provenance records describe TV Box Tools commit `dbcc3e7b749e3d1c7ad50ee6dd85897010fff4a5` as architecture research only, with no copied implementation, assets, endpoints or dependency. The Apache license template was reused. Source equivalence to its release 1.26.10.17 was not proven and is not asserted. The license template is byte-identical to that snapshot before this preparation. Its publisher appendix was changed to redbone labs; the original Copyright 2026 Smartago LLC notice and modification record are retained in NOTICE and the packaged project notice. No Smartago implementation match was found in the comparison described below.

Native transport, typed commands and journal are recorded as independently written. AOSP protocol facts informed wire/RSA/shell-v2 behavior; no AOSP implementation is recorded as copied. Reference: https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/protocol.md . This is not a claim of line-by-line legal certification.

If any implementation was copied/adapted, record exact revision/files, retain relevant upstream copyrights/license/NOTICE and mark modified files before release. Do not ship Smartago brand assets. Apache redistribution obligations: https://www.apache.org/licenses/LICENSE-2.0 (§4).

## Actual-source comparison — 26 October 2026

Compared the 30 allowlisted Kotlin/script/XML files to 123 implementation/resource files in the local TV Box Tools snapshot at the pinned commit. Byte comparison and import/comment-stripped 24-token windows found no implementation matches. Manual review compared AdbIdentity.kt/AdbSocketPlugin.kt and core steps/engine: upstream uses Capacitor/Tango/Kadb/TLS paths and a general command engine; this app uses its own classic ADB framing/RSA, Keystore-wrapped identity, typed commands and journal. HOME selection, stock-priority handling and TCP/authentication are shared concepts/protocol operations, not evidence of copied expression. This does not prove absence of renamed/translated reuse or unknown third-party origins; no equivalence to TV Box Tools 1.26.10.17 is claimed.

Directly reused items established by audit: the TV Box Tools Apache license template (attributed above); official JetBrains binaries/copyright/license text; and original Launcher Manager files. Exact inherited backup matches are DiscoveryModel.kt, LauncherRepository.kt, SafetyModel.kt, HelperWire.kt, JournalCodec.kt, DiscoveryTests.kt and SafetyTests.kt. Their names/locations are visible in the public tree. Owner confirmed on 26 October 2026: they commissioned the original LauncherManager, have sufficient rights to release inherited source/project assets under Apache-2.0 as redbone labs, know of no third-party implementation/assets beyond documented reuse, and have disclosed other copied material or restrictions. Source-rights gate cleared on that representation; this is not inferred from AI generation. No original evidence was deleted; applicable third-party notices remain retained.

No evidence of directly reused TV Box Tools/AOSP implementation was found in the bounded comparison. AOSP wire constants, RSA layout and shell-v2 framing are documented protocol facts. This is a source/provenance audit, not a universal legal certification. If later evidence identifies adapted implementation, retain that source's license/copyright and mark changes before distribution.

