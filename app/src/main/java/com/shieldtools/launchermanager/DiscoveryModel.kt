package com.shieldtools.launchermanager

/** A HOME validation failure does not imply failed ADB authentication or transport. */
class HomeValidationException(message: String) : IllegalStateException(message)

enum class HomeStatus { SELECTED, NEEDS_CHOICE, UNVERIFIED }

data class HomeResolution(val status: HomeStatus, val component: String?, val guidance: String) {
    fun isCurrent(component: String) = status == HomeStatus.SELECTED && this.component == HomeRules.canonical(component)
}

/** Read-only resolution policy. Never use foreground activity as evidence of a default. */
object HomeRules {
    const val CHOICE_GUIDANCE = "No default HOME is selected. Press HOME on the remote, choose your launcher in Android's chooser, and select Always if offered. Then return here and Refresh. Switching stays blocked until a default is verified."
    private val observedComponent = Regex("(?:android|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+)/\\.?[A-Za-z_][A-Za-z0-9_.$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)*")
    fun canonical(value: String?): String? {
        if (value == null || !observedComponent.matches(value)) return null
        val (pkg, activity) = value.split('/', limit = 2)
        return "$pkg/${if (activity.startsWith('.')) pkg + activity else activity}"
    }
    fun resolve(raw: String?, homes: List<HomeEntry>): HomeResolution {
        val component = canonical(raw)
        if (raw.isNullOrBlank() || component in setOf("android/com.android.internal.app.ResolverActivity", "android/com.android.internal.app.ChooserActivity"))
            return HomeResolution(HomeStatus.NEEDS_CHOICE, null, CHOICE_GUIDANCE)
        val home = homes.singleOrNull { canonical(it.component) == component }
        if (component == null || home == null || !home.launchable || LauncherSafety.blockedReason(home) != null)
            return HomeResolution(HomeStatus.UNVERIFIED, null, "Android's default HOME could not be verified from the launcher inventory. Refresh and review Android's HOME selection; no launcher changes are allowed.")
        return HomeResolution(HomeStatus.SELECTED, component, "")
    }
    fun requireAgreement(appRaw: String?, shellOutput: String, homes: List<HomeEntry>): HomeResolution {
        val lines = shellOutput.lineSequence().map { it.trim() }.toList()
        val components = lines.filter { observedComponent.matches(it) }
        val shellRaw = when {
            components.size == 1 -> components.single()
            components.isEmpty() && shellOutput.trim() == "No activity found" -> null
            else -> throw HomeValidationException("The shell HOME result could not be verified. Refresh before choosing a launcher; no changes were made.")
        }
        val app = resolve(appRaw, homes)
        val shell = resolve(shellRaw, homes)
        if (app.status == HomeStatus.UNVERIFIED || shell.status == HomeStatus.UNVERIFIED)
            throw HomeValidationException("HOME metadata is incomplete or restricted. Refresh and review Android's HOME selection; no changes were made.")
        if (app.status != shell.status || app.component != shell.component)
            throw HomeValidationException("Android and shell HOME readings disagree. Refresh and choose a default through Android's HOME interface before switching. No changes were made.")
        return app
    }
}

/** Android 11 does not parcel ResolveInfo.isDefault. Use the filter or a DEFAULT-only query. */
data class DefaultEvidence(val filterDeclaresDefault: Boolean?, val matchedDefaultOnlyQuery: Boolean, val reportedIsDefault: Boolean)

data class PackageObservation(val packageName: String, val installed: Boolean?, val enabled: Boolean?, val homeConfirmed: Boolean, val detail: String) {
    val status: String get() = when {
        installed != true -> "Not visible to this app; installation status unconfirmed"
        enabled == null -> "Installed; enabled state unknown"
        enabled -> "Installed and enabled"
        else -> "Installed but disabled"
    }
}

data class DiscoverySummary(val homes: List<HomeEntry>, val cardHomes: List<HomeEntry>, val excluded: List<HomeEntry>, val resolvedHome: String?, val warnings: List<String>) {
    val currentHome: HomeEntry? get() = homes.firstOrNull { it.component == resolvedHome }
    val emptyMessage: String get() = when {
        cardHomes.isNotEmpty() -> ""
        warnings.isNotEmpty() -> "Launcher discovery is incomplete. Open Advanced Status for details; no packages were changed."
        resolvedHome != null -> "Android resolves HOME, but its launcher details are not visible. Open Advanced Status; this does not mean it is absent."
        else -> "No eligible HOME activities are visible to this app. Disabled or restricted packages may not be discoverable."
    }
}

object DiscoveryRules {
    fun hasDefault(evidence: DefaultEvidence): Boolean = evidence.filterDeclaresDefault == true || evidence.matchedDefaultOnlyQuery
    fun enabledText(known: Boolean, value: Boolean): String = if (!known) "unknown (read restricted or unavailable)" else if (value) "enabled" else "disabled"
    fun summarize(entries: List<HomeEntry>, resolvedHome: String?, warnings: List<String> = emptyList()): DiscoverySummary {
        val unique = entries.distinctBy { it.component }
        val extra = if (resolvedHome != null && unique.none { it.component == resolvedHome })
            listOf("Resolved HOME $resolvedHome was not returned with readable activity metadata. Visibility is limited; it is not reported as absent.") else emptyList()
        return DiscoverySummary(unique, unique.filter { LauncherSafety.blockedReason(it) == null }, unique.filter { LauncherSafety.blockedReason(it) != null }, resolvedHome, (warnings + extra).distinct())
    }
}
