package com.shieldtools.launchermanager

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
