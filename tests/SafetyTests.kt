package com.shieldtools.launchermanager

private var passed = 0
private fun test(name: String, body: () -> Unit) {
    body(); passed++; println("PASS $name")
}
private fun home(pkg: String, priority: Int = 0, enabled: Boolean = true) = HomeEntry(
    packageName = pkg, activityName = "$pkg.DiscoveredHome", label = pkg, priority = priority,
    installed = true, appEnabled = enabled, componentEnabled = true, exported = true,
    suspended = false, systemApp = pkg == LauncherSafety.STOCK, persistent = false,
    systemUid = false, setupCategory = false, defaultCategory = true,
    versionCode = 1, signerDigest = "a".repeat(64), appEnabledSetting = if (enabled) 0 else 3,
    componentEnabledSetting = 0
)

fun main() {
    val current = home(LauncherSafety.DETOX)
    val target = home("org.example.launcher")
    val stock = home(LauncherSafety.STOCK, 2)
    val helper = HelperCapability(true, true, "session-1")
    val proof = RecoveryProof(current.component, true, true)
    fun plan(
        homes: List<HomeEntry> = listOf(current, target), chosen: HomeEntry = target,
        capability: HelperCapability = helper, recovery: RecoveryProof = proof,
        confirmed: String? = chosen.component, trust: Boolean = true, disableStock: Boolean = false,
        resolved: String? = current.component, owner: Boolean = true
    ) = SwitchPlanner.prepare(LauncherSnapshot(homes, resolved, owner), chosen, capability, recovery, confirmed, trust, disableStock)
    fun blocked(value: PlanResult) = check(value is PlanResult.Blocked) { "Expected blocked plan: $value" }

    test("explicit disabled component overrides an enabled manifest") { check(!LauncherSafety.effectiveEnabled(2, true)) }
    test("default state honors disabled manifest") { check(!LauncherSafety.effectiveEnabled(0, false)) }
    test("explicit enabled restores declared-disabled component state") { check(LauncherSafety.effectiveEnabled(1, false)) }
    test("disabled-user and disabled-until-used cannot preview") { check(!LauncherSafety.effectiveEnabled(3, true)); check(!LauncherSafety.effectiveEnabled(4, true)) }
    test("future unknown HOME package is supported dynamically") { check(LauncherSafety.canPreview(target)); check(plan() is PlanResult.Ready) }
    test("every protected package is excluded even if it advertises HOME") {
        LauncherSafety.protectedPackages.forEach { check(!LauncherSafety.canPreview(home(it))); blocked(plan(chosen = home(it), homes = listOf(current, home(it)))) }
    }
    test("setup and persistent/system-UID HOME components are excluded") {
        listOf(target.copy(setupCategory = true), target.copy(persistent = true), target.copy(systemUid = true)).forEach { check(LauncherSafety.blockedReason(it) != null) }
    }
    test("unreviewed system HOME is excluded while stock remains recognizable") { check(LauncherSafety.blockedReason(target.copy(systemApp = true)) != null); check(LauncherSafety.blockedReason(stock) == null) }
    test("HOME without DEFAULT is not selectable") { check(LauncherSafety.blockedReason(target.copy(defaultCategory = false)) != null) }
    test("invalid component identity is rejected") { check(LauncherSafety.blockedReason(target.copy(activityName = "bad; command")) != null); check(!LauncherSafety.validPackage("org.foo;cmd")) }
    test("disabled or suspended preview is unavailable") { check(!LauncherSafety.canPreview(target.copy(appEnabled = false))); check(!LauncherSafety.canPreview(target.copy(suspended = true))) }
    test("unauthenticated/missing/stale-generation helper cannot plan a switch") {
        blocked(plan(capability = helper.copy(available = false)))
        blocked(plan(capability = helper.copy(authenticated = false)))
        blocked(plan(capability = helper.copy(generation = null)))
    }
    test("secondary user rejected") { blocked(plan(owner = false)) }
    test("missing and duplicate target rejected") { blocked(plan(homes = listOf(current))); blocked(plan(homes = listOf(current, target, target))) }
    test("target version or signer drift requires refresh and new confirmation") {
        blocked(plan(homes = listOf(current, target.copy(versionCode = 2))))
        blocked(plan(homes = listOf(current, target.copy(signerDigest = "b".repeat(64)))))
    }
    test("unavailable signer blocks mutation plan") { val unknown = target.copy(signerDigest = "unavailable"); blocked(plan(homes = listOf(current, unknown), chosen = unknown)) }
    test("no confirmation or trust cannot switch") { blocked(plan(confirmed = null)); blocked(plan(trust = false)); blocked(plan(confirmed = stock.component)) }
    test("unknown current HOME or system recovery screen cannot be treated as working") { blocked(plan(resolved = null)); blocked(plan(resolved = "android/Resolver")) }
    test("durable journal and tested exact recovery HOME are mandatory") {
        blocked(plan(recovery = proof.copy(tested = false))); blocked(plan(recovery = proof.copy(journalDurable = false)))
        blocked(plan(recovery = proof.copy(workingHome = stock.component)))
    }
    test("stock priority conflict cannot be silently disabled") { blocked(plan(homes = listOf(current, target, stock))) }
    test("stock-only fallback is planned after target opening and preferred request") {
        val ready = plan(homes = listOf(current, target, stock), disableStock = true) as PlanResult.Ready
        val steps = ready.steps
        check(steps.indexOfFirst { it is PlannedStep.VerifyTargetOpens } < steps.indexOfFirst { it is PlannedStep.DisableStock })
        check(steps.indexOfFirst { it is PlannedStep.RequestPreferredHome } < steps.indexOfFirst { it is PlannedStep.DisableStock })
        check(steps.last() is PlannedStep.VerifyEffectiveHome)
    }
    test("higher-priority non-stock HOME never causes another disable") { blocked(plan(homes = listOf(current, target, home("org.other.launcher", 3)), disableStock = true)) }
    test("disabled target needs enabling before its preflight opening") {
        val disabled = target.copy(appEnabled = false, appEnabledSetting = 3)
        val ready = plan(homes = listOf(current, disabled), chosen = disabled) as PlanResult.Ready
        check(ready.steps.first() == PlannedStep.EnableTarget(target.packageName, 3))
    }
    test("component-level overrides are never planned") { val off = target.copy(componentEnabled = false); blocked(plan(homes = listOf(current, off), chosen = off)) }
    test("selecting current HOME is a no-op, not a transaction") { blocked(plan(homes = listOf(current), chosen = current)) }
    test("shipped backend never claims successful switching or restores") {
        val backend: SwitchingBackend = UnavailableBackend()
        check(backend.requestDefault(target) is SwitchOutcome.Unavailable)
        check(backend.restorePrevious() is SwitchOutcome.Unavailable)
        check(backend.restoreStock() is SwitchOutcome.Unavailable)
    }
    println("$passed safety tests passed. No Android device or privileged executor used.")
}
