package com.shieldtools.launchermanager

import java.io.File

private var discoveryPassed = 0
private fun discoveryTest(name: String, body: () -> Unit) { body(); discoveryPassed++; println("PASS $name") }
private fun sampleHome(pkg: String = "org.example.futurelauncher") = HomeEntry(
    pkg, "$pkg.Home", pkg, 0, true, true, true, true, false,
    false, false, false, false, true, 1, "a".repeat(64), 0, 0
)

fun main(args: Array<String>) {
    val fixture = File(args.single()).readLines().filter { it.isNotBlank() }.drop(1).map { it.split('\t') }
    val homes = fixture.map { row -> sampleHome(row[0]).copy(
        activityName = row[1], priority = row[2].toInt(), appEnabledSetting = row[3].toInt(), componentEnabledSetting = row[4].toInt(),
        appEnabled = LauncherSafety.effectiveEnabled(row[3].toInt(), true), componentEnabled = LauncherSafety.effectiveEnabled(row[4].toInt(), true),
        systemApp = row[0] == LauncherSafety.STOCK,
        defaultCategory = DiscoveryRules.hasDefault(DefaultEvidence(row[5].toBoolean(), false, row[6].toBoolean())), versionCode = row[7].toLong()
    ) }
    val detox = homes.single { it.packageName == LauncherSafety.DETOX }
    val stock = homes.single { it.packageName == LauncherSafety.STOCK }
    val summary = DiscoveryRules.summarize(homes, detox.component)
    discoveryTest("captured Android 11 metadata reproduces the old zero-card result") {
        check(fixture.size == 2); check(fixture.all { !it[6].toBoolean() }); check(fixture.all { it[5].toBoolean() })
        val legacy = homes.mapIndexed { i, item -> item.copy(defaultCategory = fixture[i][6].toBoolean()) }
        check(DiscoveryRules.summarize(legacy, detox.component).cardHomes.isEmpty())
    }
    discoveryTest("corrected DEFAULT evidence retains both captured launcher cards") { check(summary.cardHomes.size == 2); check(summary.excluded.isEmpty()) }
    discoveryTest("current HOME resolves by exact component despite generic app label") { check(summary.currentHome?.component == detox.component); check(summary.currentHome?.versionCode == 15L) }
    discoveryTest("installed disabled stock remains a card but cannot preview") { check(stock in summary.cardHomes); check(stock.installed); check(!stock.appEnabled); check(!LauncherSafety.canPreview(stock)) }
    discoveryTest("installed enabled TV Detox can preview") { check(LauncherSafety.canPreview(detox)); check(detox.appEnabledSetting == 0) }
    discoveryTest("empty discovery does not claim packages are uninstalled") { val empty = DiscoveryRules.summarize(emptyList(), null); check(empty.cardHomes.isEmpty()); check(empty.emptyMessage.contains("may not be discoverable")) }
    discoveryTest("failed queries surface incomplete discovery instead of install advice") { val empty = DiscoveryRules.summarize(emptyList(), null, listOf("HOME query: SecurityException")); check(empty.emptyMessage.contains("incomplete")); check(empty.warnings.size == 1) }
    discoveryTest("resolved HOME omitted from discovery is preserved as limited visibility") { val empty = DiscoveryRules.summarize(emptyList(), detox.component); check(empty.resolvedHome == detox.component); check(empty.currentHome == null); check(empty.warnings.single().contains("not reported as absent")) }
    discoveryTest("DEFAULT-only query recovers proof when resolved filter is missing") { check(DiscoveryRules.hasDefault(DefaultEvidence(null, true, false))) }
    discoveryTest("legacy isDefault alone never grants eligibility") { check(!DiscoveryRules.hasDefault(DefaultEvidence(null, false, true))); check(!DiscoveryRules.hasDefault(DefaultEvidence(false, false, true))) }
    discoveryTest("duplicate query results deduplicate by activity not package") { val other = detox.copy(activityName = "com.ansub.tvhome.AlternateHome"); val merged = DiscoveryRules.summarize(listOf(detox, detox, other), detox.component); check(merged.cardHomes.size == 2) }
    discoveryTest("future launcher package remains dynamically supported") { check(DiscoveryRules.summarize(listOf(sampleHome()), null).cardHomes.size == 1) }
    discoveryTest("system fallback and setup HOME are diagnostics rather than launcher controls") { val fallback = sampleHome("com.android.tv.settings").copy(systemUid = true); val setup = sampleHome("org.vendor.setup"); val found = DiscoveryRules.summarize(listOf(detox, fallback, setup), detox.component); check(found.cardHomes == listOf(detox)); check(found.excluded.size == 2) }
    discoveryTest("protected application advertising HOME remains excluded") { check(DiscoveryRules.summarize(listOf(sampleHome("ar.tvplayer.tv")), null).cardHomes.isEmpty()) }
    discoveryTest("restricted application state retains identity but blocks preview and says unknown") { val unknown = detox.copy(appStateKnown = false, appEnabled = false, appEnabledSetting = -1); check(DiscoveryRules.summarize(listOf(unknown), detox.component).cardHomes.size == 1); check(!LauncherSafety.canPreview(unknown)); check(DiscoveryRules.enabledText(false, false).startsWith("unknown")) }
    discoveryTest("restricted component state is never interpreted as enabled") { val unknown = detox.copy(componentStateKnown = false, componentEnabled = true); check(!LauncherSafety.canPreview(unknown)); check(DiscoveryRules.enabledText(false, true).startsWith("unknown")) }
    discoveryTest("component disable overrides enabled package and manifest") { val off = detox.copy(componentEnabled = LauncherSafety.effectiveEnabled(2, true), componentEnabledSetting = 2); check(off in DiscoveryRules.summarize(listOf(off), off.component).cardHomes); check(!LauncherSafety.canPreview(off)) }
    discoveryTest("optional signer/version failure does not erase a HOME card") { val partial = detox.copy(versionCode = -1, signerDigest = "unavailable"); val found = DiscoveryRules.summarize(listOf(partial), detox.component, listOf("signing metadata unavailable")); check(found.cardHomes == listOf(partial)); check(found.warnings.isNotEmpty()) }
    discoveryTest("invisible known recovery package is unconfirmed rather than absent") { val status = PackageObservation(LauncherSafety.STOCK, null, null, false, "visibility limited"); check(status.status.contains("unconfirmed")); check(!status.status.contains("uninstalled")) }
    discoveryTest("metadata-only disabled package is distinct from confirmed HOME activity") { val status = PackageObservation(LauncherSafety.STOCK, true, false, false, "HOME activity not visible"); check(status.status == "Installed but disabled"); check(!status.homeConfirmed); check(PackageObservation(LauncherSafety.STOCK, true, null, false, "restricted").status.contains("unknown")) }
    println("$discoveryPassed discovery tests passed. Fixture and policy tests only; no Android device used.")
}
