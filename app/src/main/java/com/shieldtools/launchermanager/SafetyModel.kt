package com.shieldtools.launchermanager

/** Platform-independent policy. No side effects or shell/ADB command construction. */
data class HomeEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val priority: Int,
    val installed: Boolean,
    val appEnabled: Boolean,
    val componentEnabled: Boolean,
    val exported: Boolean,
    val suspended: Boolean,
    val systemApp: Boolean,
    val persistent: Boolean,
    val systemUid: Boolean,
    val setupCategory: Boolean,
    val defaultCategory: Boolean,
    val versionCode: Long,
    val signerDigest: String,
    val appEnabledSetting: Int,
    val componentEnabledSetting: Int,
    val appStateKnown: Boolean = true,
    val componentStateKnown: Boolean = true
) {
    val component: String get() = "$packageName/$activityName"
    val launchable: Boolean get() = installed && appStateKnown && componentStateKnown && appEnabled && componentEnabled && exported && !suspended
}

object LauncherSafety {
    const val STOCK = "com.google.android.tvlauncher"
    const val DETOX = "com.ansub.tvhome"
    const val MANAGER = "com.shieldtools.launchermanager"

    val protectedPackages: Set<String> = setOf(
        "ar.tvplayer.tv", "com.google.android.youtube.tv", "com.plexapp.android",
        "com.disney.disneyplus", "com.netflix.ninja", "tv.pluto.android",
        "com.espn.score_center", "com.amazon.amazonvideo.livingroom",
        "com.amazon.amazonvideo.livingroom.nvidia", "com.google.android.videos",
        "com.hulu.livingroomplus", "com.pandora.android.atv", "com.fgl27.twitch",
        "tv.twitch.android.app", "com.google.android.youtube.tvmusic",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.mediashell", "com.google.android.katniss",
        "com.google.android.tv.remote.service", "com.android.tv.settings",
        "com.android.settings", "com.android.systemui", "android",
        "com.google.android.tungsten.setupwraith", "com.nvidia.ota",
        "com.nvidia.KeyComboOta", "com.android.provision"
    )

    fun blockedReason(entry: HomeEntry): String? = when {
        entry.packageName == MANAGER -> "The manager is not a replacement HOME."
        entry.packageName in protectedPackages -> "Protected application or system recovery component."
        entry.setupCategory -> "Setup wizard HOME activity; excluded for safety."
        !entry.defaultCategory -> "HOME filter lacks the DEFAULT category."
        entry.persistent || entry.systemUid -> "Persistent/system-UID HOME component; excluded for safety."
        entry.packageName.contains("setup", true) || entry.packageName.contains("wraith", true) ||
            entry.packageName.contains("provision", true) -> "Setup/provisioning component; excluded for safety."
        entry.systemApp && entry.packageName != STOCK -> "Unreviewed system HOME component; excluded from controls."
        !validPackage(entry.packageName) || !validActivity(entry.activityName) -> "Invalid component identity."
        else -> null
    }

    fun canPreview(entry: HomeEntry): Boolean = blockedReason(entry) == null && entry.launchable

    fun validPackage(value: String): Boolean = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(value)
    fun validActivity(value: String): Boolean = Regex("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)+").matches(value)

    /** Android's explicit user setting takes precedence over the manifest declaration. */
    fun effectiveEnabled(explicit: Int, manifestEnabled: Boolean): Boolean = when (explicit) {
        0 -> manifestEnabled
        1 -> true
        2, 3, 4 -> false
        else -> false
    }
}

data class LauncherSnapshot(val homes: List<HomeEntry>, val resolvedHome: String?, val ownerUser: Boolean) {
    val homeResolution: HomeResolution get() = HomeRules.resolve(resolvedHome, homes)
}
data class HelperCapability(val available: Boolean, val authenticated: Boolean, val generation: String?)
data class RecoveryProof(val workingHome: String?, val tested: Boolean, val journalDurable: Boolean)

sealed class PlannedStep {
    data class EnableTarget(val packageName: String, val previousState: Int) : PlannedStep()
    data class VerifyTargetOpens(val component: String) : PlannedStep()
    data class RequestPreferredHome(val component: String) : PlannedStep()
    data class DisableStock(val previousState: Int) : PlannedStep()
    data class VerifyEffectiveHome(val component: String) : PlannedStep()
}

sealed class PlanResult {
    data class Ready(val target: String, val steps: List<PlannedStep>) : PlanResult()
    data class Blocked(val reason: String) : PlanResult()
}

/** Future-helper preflight specification only; nothing in this class executes a step. */
object SwitchPlanner {
    fun prepare(
        snapshot: LauncherSnapshot,
        target: HomeEntry,
        helper: HelperCapability,
        recovery: RecoveryProof,
        confirmedTarget: String?,
        trustConfirmed: Boolean,
        allowStockDisable: Boolean
    ): PlanResult {
        fun deny(reason: String) = PlanResult.Blocked(reason)
        if (!helper.available || !helper.authenticated || helper.generation.isNullOrBlank()) return deny("No authenticated switching helper.")
        if (!snapshot.ownerUser) return deny("Only the approved owner user is supported.")
        val live = snapshot.homes.singleOrNull { it.component == target.component }
            ?: return deny("Target not uniquely present in the fresh HOME inventory.")
        if (live != target) return deny("Target state changed; refresh and reconfirm.")
        LauncherSafety.blockedReason(target)?.let { return deny(it) }
        if (!target.installed || !target.exported || target.suspended) return deny("Target unavailable, unexported or suspended.")
        if (!target.appStateKnown || !target.componentStateKnown) return deny("Target enabled state cannot be verified.")
        if (!Regex("[0-9a-f]{64}(,[0-9a-f]{64})*").matches(target.signerDigest)) return deny("Target signing identity unavailable.")
        if (!target.componentEnabled) return deny("Target HOME component disabled; no component-level overrides are allowed.")
        if (confirmedTarget != target.component || !trustConfirmed) return deny("On-screen target/trust confirmation missing.")
        val current = snapshot.homes.singleOrNull { it.component == snapshot.resolvedHome }
            ?: return deny("Effective current HOME is unknown or ambiguous.")
        if (!current.launchable || LauncherSafety.blockedReason(current) != null) return deny("Current HOME is not a managed working launcher.")
        if (!recovery.tested || !recovery.journalDurable || recovery.workingHome != current.component) return deny("Durable journal and tested previous-HOME recovery required.")
        if (current.component == target.component) return deny("Target already resolves as HOME; no change required.")
        val competitors = snapshot.homes.filter { it.launchable && it.component != target.component && it.priority > target.priority }
        if (competitors.any { it.packageName != LauncherSafety.STOCK }) return deny("Higher-priority non-stock HOME interferes; no automatic disable permitted.")
        val stockConflict = competitors.firstOrNull { it.packageName == LauncherSafety.STOCK }
        if (stockConflict != null && !allowStockDisable) return deny("Stock-priority conflict needs separate, explicit stock-only confirmation.")
        val steps = mutableListOf<PlannedStep>()
        if (!target.appEnabled) steps += PlannedStep.EnableTarget(target.packageName, target.appEnabledSetting)
        steps += PlannedStep.VerifyTargetOpens(target.component)
        steps += PlannedStep.RequestPreferredHome(target.component)
        if (stockConflict != null) steps += PlannedStep.DisableStock(stockConflict.appEnabledSetting)
        steps += PlannedStep.VerifyEffectiveHome(target.component)
        return PlanResult.Ready(target.component, steps)
    }
}

sealed class SwitchOutcome {
    data class Unavailable(val reason: String) : SwitchOutcome()
}

/** No executor exists in Phase 6A. A future implementation needs its own reviewed privilege boundary. */
interface SwitchingBackend {
    val description: String
    fun requestDefault(target: HomeEntry): SwitchOutcome
    fun restorePrevious(): SwitchOutcome
    fun restoreStock(): SwitchOutcome
}

class UnavailableBackend : SwitchingBackend {
    override val description = "Privileged helper not connected. Preview and status are available; managed switching and recovery are unavailable."
    override fun requestDefault(target: HomeEntry) = SwitchOutcome.Unavailable(description)
    override fun restorePrevious() = SwitchOutcome.Unavailable("No managed switch history or authorized helper. Previous HOME cannot be restored by this app.")
    override fun restoreStock() = SwitchOutcome.Unavailable("Stock may be disabled. A normal app cannot re-enable it. An authorized helper or the external tested recovery procedure is required.")
}
